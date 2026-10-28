package net.beacon.dmr;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * MMDVM / Homebrew 协议的 UDP 会话（BrandMeister、TGIF、XLX 等主站通用）。
 *
 * <p>协议来自 MMDVMHost（G4KLX）与 DroidStar（nostar）的实现，逐字节对照：
 *
 * <pre>
 * 登录握手
 *   客户端 → RPTL  + id(4, BE)                                    8 字节
 *   主站  → RPTACK + id(4) + salt(4)                              10 字节
 *   客户端 → RPTK  + id(4) + SHA256(salt_bytes ‖ password)        40 字节
 *   主站  → RPTACK + id(4)                                        10 字节
 *   客户端 → RPTC  + id(4) + 配置串(294)                          302 字节
 *   主站  → RPTACK + id(4)                                        10 字节   → 可收发
 *
 * 保活
 *   客户端 → RPTPING + id(4)                                      11 字节（每 5 秒）
 *   主站  → MSTPONG + id(4)                                       11 字节
 *
 * 业务
 *   DMRD 双向，55 字节（见 DmrTx）
 * </pre>
 *
 * <p><b>上面五个包里的 id 是「登录 ID」，不一定等于 DMR ID：</b>
 * 配了 BrandMeister 的 ESSID 别名之后它是 {@code dmrId * 100 + (essid - 1)}
 * （例如 4614187 + 04 → 461418703）。<b>注意这个 -1：</b>它是为了和 DroidStar
 * 的 {@code dmr.cpp} 对齐 —— 同一个 ESSID 数字在两边必须落到同一个登录 ID，
 * 否则「同样的配置它连得上、我们连不上」。见 {@link #essid} 的说明 ——
 * 主站按 Radio ID 认设备，一个 ID 只允许一台热点在线；
 * 名下已经有一台热点在用同一个裸 ID 时，必须靠别名才能并存。
 *
 * <p>两个易错点，已按上游实现处理：
 * <ul>
 *   <li>salt 是「4 字节位模式」，直接按原始字节顺序拼在密码前面做 SHA-256，
 *       既不做字节序转换，也不当整数解析。</li>
 *   <li>RPTACK 出现三次，含义完全不同，只能靠本地状态机区分，
 *       报文本身没有任何区分标志。</li>
 * </ul>
 *
 * <p>本类只负责连接与收发。DMRD 包由 {@link DmrTx} 组装，音频由 {@link DmrTransmitter} 采集。
 */
public final class MmdvmSession {

    /** MMDVM/Homebrew 默认端口 */
    public static final int DEFAULT_PORT = 62031;

    /** 保活间隔，与 DroidStar 的 ping 定时器一致 */
    private static final int PING_INTERVAL_MS = 5000;

    /** 接收循环的超时，用来周期性检查 running 标志 */
    private static final int RECV_TIMEOUT_MS = 500;

    /** 主站报文最长可能到 302+，留足余量 */
    private static final int RX_BUFFER = 600;

    /**
     * 本机 UDP 源端口 —— <b>整个进程生命周期内固定不变</b>。
     *
     * <p>这是与真实热点对齐的关键一项。Pi-Star / MMDVMHost 在 MMDVM.ini 里
     * 写死 `Local=62031`，一台热点一辈子只用这一个端口；而主站是
     * <b>按「IP + 源端口」来认一台热点</b>的。
     *
     * <p>本 App 早期版本每次连接、每次重试都 `new DatagramSocket()`，
     * 也就是每轮换一个随机临时端口 —— 主站眼里同一个 Radio ID 下不断
     * 冒出新热点，于是按「一个 Radio ID 只允许一台热点在线」的规则
     * 在 RPTC 阶段回 MSTNAK。表现就是「密码明明是对的，却总在配置这一步被拒」，
     * 而且越重试越糟：每重试一次就多留一台幽灵热点。
     */
    private static final int LOCAL_PORT_PREFERRED = 62031;

    /** 62031 被占用时往后顺延试几个端口 */
    private static final int LOCAL_PORT_TRIES = 20;

    /** 握手重试间隔：主站/网络偶尔会吞掉第一个包，必须重发 */
    private static final int HANDSHAKE_RETRY_MS = 3000;

    /** 握手总超时：超过就报错并给出可执行的排查建议 */
    private static final int HANDSHAKE_TIMEOUT_MS = 24000;

    /**
     * 被主站拒绝（MSTNAK）后等待多久再重试，逐次加长。
     *
     * <p>BrandMeister 对同一个 Radio ID <b>只允许一个会话在线</b> ——
     * 官方 FAQ 原文：「You will set the Hotspot Security password for the Radio ID
     * that is being used and then may use that same password on multiple devices
     * if using the same Radio ID for each one (<b>plus the alias id if using
     * multiple hotspots</b>).」也就是说同一个 ID 想挂两个热点必须另配 alias id。
     *
     * <p>旧会话要多久才被主站回收，取决于主站那边的超时（官方 wiki 的 UK 页面写着
     * Hand-Off Timer: 15 秒，但<b>实测不像只有 15 秒</b>：用户「反复点十几次才连上」
     * 意味着窗口在 30~60 秒量级）。所以这里按 10/20/40/60 秒逐档加长，
     * 累计约两分钟，足以覆盖 60 秒级的回收时间。
     *
     * <p>所以 MSTNAK <b>是暂时性的</b>，绝不能当致命错误 ——
     * 等窗口过去自动重试即可，用户不必反复点「连接」。
     */
    /* ★ 首档从 10 秒提到 15 秒。
     *
     * BrandMeister 官方 wiki 里 Hand-Off Timer 写的是 15 秒 —— 也就是说
     * 被拒之后**至少**要等这么久，旧会话才可能被回收。原来首档 10 秒
     * 刚好差一点，第一次重试必然又撞一次，白丢一轮。
     * 后四档不动（20/30/45/60），累计从 165 秒变成 170 秒，覆盖 60 秒级回收绰绰有余。 */
    private static final int[] NAK_WAITS_MS = {15000, 20000, 30000, 45000, 60000};

    /** 连续被拒多少次才放弃（前 5 次都会自动重试，累计等待约 165 秒） */
    private static final int MAX_NAK = 6;

    /**
     * 主动断开后、再次连接前的缓冲。
     *
     * <p>断开时我们已经发了 RPTCL 登出，主站正常会立刻释放会话；
     * 但 UDP 会丢包，RPTCL 丢了会话就得靠计时器回收。
     * 这里只等一小会儿给 RPTCL 生效的机会；真没生效的话，
     * 接下来的 MSTNAK 自动重试链会兜住。
     */
    private static final int POST_DISCONNECT_WAIT_MS = 2000;

    /**
     * 主站主动关闭连接（MSTCL）后的自动重连。
     *
     * <p>上游 DroidStar 在收到 MSTCL 时是 `emit request_reconnect(10000)` ——
     * 10 秒后重连。我们也照做，但要设次数上限，否则凭据真的不对时会无限循环。
     * MSTCL 最常见的原因是「同一个 Radio ID 被别的设备顶掉」或主站重启。
     */
    private static final int MAX_AUTO_RECONNECT = 3;
    private static final int AUTO_RECONNECT_WAIT_MS = 10000;

    /** 握手停滞多久就从头重来（UDP 会丢包，RPTK/RPTC 丢了不能干等） */
    private static final int STALL_MS = 8000;

    /* 判定「链路已死」的实际时长不再是独立常量，而是
     * PONG_MISS_LIMIT × PING_INTERVAL_MS（12 × 5 秒 = 60 秒）。
     *
     * 原来这里有个 PONG_TIMEOUT_MS = 25000，但 pingLoop 从头到尾没用它 ——
     * 判据早就改成数 ping 个数了，那个常量只剩一句「25 秒」的过期说明，
     * 日志里也照着它打，于是日志说 25 秒、代码实际给 60 秒。
     * 现在日志直接按公式算，两处不会再对不上。 */

    /**
     * 连续发多少个 RPTPING 没换回 MSTPONG 才算真断。
     *
     * <p><b>为什么不直接用墙上时钟判断</b>：熄屏后系统会冻结进程
     * （Doze / App Standby / 厂商省电），ping 线程几秒到几分钟跑不到。
     * 一恢复运行，用 {@code now - lastPongMs} 算出来的「沉默时长」早就超过 25 秒，
     * 于是明明链路好好的，却被判定「心跳超时」去重连 —— 用户看到的就是
     * 「待机时莫名提示心跳超时正在重连」。
     *
     * <p>换成数「发了几个 ping 还没回」就免疫这个问题：进程被冻结时
     * 计数不会增长，只有真的发了 5 个 ping 都没回应才判定断开。
     *
     * <p>★ v2.4.8 从 5 提到 12。用户反馈「长时间连着不发射，会提示心跳超时」——
     * 挂机守听时本来就一个包都没有，此时「没收到 pong」和「链路断了」在本地
     * 观测上是同一个现象。5 个 ping = 25 秒，在移动网络上一串丢包就够凑满，
     * 于是明明好好的却被判死。12 个 = 60 秒，容忍度高得多，而真断线最迟
     * 一分钟也能自己恢复 —— 守听场景下这个延迟完全可接受。
     */
    private static final int PONG_MISS_LIMIT = 12;

    public enum State {
        /** 未连接 */
        IDLE,
        /** 已发 RPTL，等待第一个 RPTACK */
        CONNECTING,
        /** 已发 RPTK（含密码摘要），等待第二个 RPTACK */
        AUTH,
        /** 已发 RPTC（配置），等待第三个 RPTACK */
        CONF,
        /** 已连通，可以收发 DMRD */
        CONNECTED,
        /** 出错或已被主站拒绝 */
        ERROR
    }

    public interface Listener {
        void onState(State state, String detail);

        /** 收到 MSTPONG，count 为累计次数 */
        void onPong(int count);

        /**
         * 收到一个 DMRD 包。
         *
         * @param srcId   来源 DMR ID
         * @param dstId   目标话组 / DMR ID
         * @param slot    时隙 1 或 2
         * @param voice   true 为语音帧（载荷里是 AMBE，可解码播放）
         * @param payload 语音帧的 33 字节载荷（内含交织过的 27 字节 AMBE，
         *                交给 {@link DmrRx#decode} 解）；非语音帧为 <b>null</b>。
         *                在收包线程上同步回调，<b>用完即弃，不要留引用</b>
         * @param flags   DMRD 头 [15] 的原始标志位，判据见 {@link DmrRx} 的 FLAG_*
         */
        void onDmrd(int srcId, int dstId, int slot, boolean voice, byte[] payload, int flags);

        void onLog(String line);
    }

    /* ---------------- 配置 ---------------- */

    private volatile String host = "";
    private volatile int port = DEFAULT_PORT;
    /**
     * 本机 Radio ID（裸 7 位，如 4614187）。呼号归属于它，
     * DMRD 业务包里的 srcId 也用它。
     */
    private volatile int dmrId = 0;
    /**
     * 热点别名 ID（ESSID），1~99；<b>0 表示不使用别名</b>。
     *
     * <p>这是 BrandMeister 的「一个 ID 挂多台热点」机制。官方文档原文：
     * 「<b>Each hotspot must have a unique ID and ESSIDs allow for this to work
     * properly on the network.</b>」第一个热点可以留空或用 01，
     * 想挂第二台就必须给其中至少一台配 01~99 的别名。
     *
     * <p><b>为什么非有它不可：</b>主站是按 Radio ID 认设备的，同一个 ID
     * <b>只允许一个会话在线</b>。如果你名下已经有一台 Pi-Star / openSPOT
     * 在用这个 ID（主站的 Device 记录里能直接看到它的型号和 last_seen），
     * 我们的 App 再用同一个裸 ID 登录，就是「同一个 ID 的第二台热点」——
     * 主站必然回 MSTNAK，而且<b>永远连不上</b>，等多久都没用
     * （这不是 hand-off 窗口的问题，是身份冲突，等窗口过去也不会好）。
     *
     * <p>配上别名之后，登录 ID 变成 {@code dmrId * 100 + (essid - 1)}
     * （例如 {@code 4614187} + {@code 04} → {@code 461418703}），
     * 在主站眼里就是一台<b>独立的热点</b>，与原来那台互不打扰。
     */
    private volatile int essid = 0;
    /**
     * 真正用于登录的 ID：{@code essid > 0 ? dmrId * 100 + (essid - 1) : dmrId}。
     *
     * <p>RPTL / RPTK / RPTC / RPTPING / RPTCL <b>五个包全用它</b>，
     * 主站据此找到对应的设备记录。而 DMRD 业务包里的 srcId 仍是裸
     * {@link #dmrId}（呼号归属那个 ID），只有 repeaterId 字段用别名 ID。
     */
    private volatile int loginId = 0;
    private volatile String password = "";

    /** 上一次「密码校验通过」（走到 CONNECTED）的时刻。
     *
     *  <p>MSTNAK 落在 AUTH 阶段时，代码原来只会说「密码不对」—— 但如果这份
     *  凭据十几分钟前刚在同一台机器上通过过，那句话会把用户引到完全错误的方向：
     *  他会去重设密码，而真正的问题在主站侧（旧会话没释放 / 临时限流）。
     *  记下这个时刻，就能在报错里直接给出「先别怀疑密码」的证据。 */
    private static volatile long lastAuthOkAtMs = 0;
    /** 上一次通过校验时的密码指纹（前 8 位十六进制，不可逆）。 */
    private static volatile String lastAuthOkFp = "";

    /* RPTC 配置串里上报的静态信息 */
    private volatile String callsign = "";
    private volatile String location = "";
    private volatile String description = "";
    private volatile String url = "";

    /** 默认 softwareId：MMDVM 固件的真实发布日期，合法 YYYYMMDD */
    public static final String DEFAULT_SWID = "20200922";
    /** 默认 packageId：MMDVM 系硬件型号，必须以 MMDVM 开头 */
    public static final String DEFAULT_PKGID = "MMDVM_MMDVM_HS_Hat";
    /**
     * RPTC 第 13 字段（softwareId），主站存成设备记录里的 firmware。
     *
     * <p><b>★ 必须是「合法的 8 位日期」（YYYYMMDD）。</b>2026-10-26 抓包实测确认：
     * BrandMeister 在 CONF 阶段会校验这个字段的格式，填 {@code "BeaconDMR"} 这种
     * 非日期串一律回 MSTNAK；而 {@code 20200922} / {@code 20210101} / {@code 20250101}
     * 这些「任意合法日期」都能通过，{@code 12345678}（1234-56-78 不是日期）不能。
     * 也就是说它校验的是<b>日期格式</b>，不是固件白名单。
     *
     * <p>我们取 MMDVM 固件的真实发布日期 {@code 20200922}（DroidStar 同款默认值），
     * 这样主站侧显示的固件版本和一台真实 MMDVM 热点没有区别。
     */
    private volatile String softwareId = DEFAULT_SWID;
    /**
     * RPTC 第 14 字段（packageId），对应上游的 `software`，主站存成设备记录里的 hardware。
     *
     * <p><b>★ 必须以 {@code MMDVM} 开头。</b>同一轮抓包实测：
     * {@code MMDVM}、{@code MMDVM_MMDVM_HS_Hat}、{@code MMDVM_MMDVM_HS_Dual_Hat}
     * 全部通过，{@code OpenGD77} 被拒，{@code BeaconDMR_Android} 也被拒。
     * 主站按这个字段判断「这是不是一台 MMDVM 系热点」。
     *
     * <p>这是 MMDVM 生态的通行做法 —— 所有软件热点（DroidStar、BlueDV 等）
     * 都上报 MMDVM 型号，主站也只接受这一类。用户可以在设置里改，
     * 但改成非 MMDVM 值会连不上，界面上会提示。
     */
    private volatile String packageId = DEFAULT_PKGID;
    /**
     * 上报的收发频率（Hz）。主站不校验它（实测填 430 或 438.8 都能连），
     * 只作为设备记录里显示的「热点频率」。取 438.800000 是为了和用户
     * 名下那几条真实记录看起来一致，不是功能必需。
     */
    private volatile long freqHz = 438_800_000L;

    /* ---------------- 运行时 ---------------- */

    private final Listener listener;
    private final Object txLock = new Object();

    private volatile DatagramSocket socket;
    /**
     * 本机固定源端口。0 表示还没定下来。
     *
     * <p>一旦选定就<b>永不更改</b>，连断开重连也复用同一个值 ——
     * 对齐真实热点 `Local=62031` 的行为。详见 {@link #LOCAL_PORT_PREFERRED}。
     */
    private volatile int localPort;
    private volatile InetAddress master;
    /** 域名解析出的全部地址。主站常有多条 A 记录，第一条不通就换下一条 */
    private volatile InetAddress[] masters = new InetAddress[0];
    private volatile int masterIdx;
    private volatile State state = State.IDLE;
    private volatile boolean running;
    private Thread rxThread;
    private Thread pingThread;
    private Thread hsThread;
    /** 保护 hsThread 的创建，避免 rx 线程与 ping 线程同时补出两个握手线程 */
    private final Object hsLock = new Object();
    private volatile int pongCount;
    /** 最近一次收到 MSTPONG 的时间；0 表示本次会话还从没收到过 pong */
    private volatile long lastPongMs;
    /**
     * 上次收到 pong 之后又发了几个 RPTPING。
     *
     * <p>用它而不是墙上时钟判断心跳超时，理由见 {@link #PONG_MISS_LIMIT}。
     */
    private volatile int pingsSincePong;
    /**
     * 「会话已建立之后」收到的 MSTNAK 条数。
     *
     * <p>这类 NAK 不能当成本次登录被拒，见 {@link #handle} 里 MSTNAK 分支的说明。
     */
    private volatile int lateNakCount;

    /* ---------------- 接收侧质量统计 ----------------
     * DMRD 的 [4] 字节是 8 位递增的包序号。主站每 60 ms 发一个，
     * 序号一旦跳变，中间少掉的那几个就是真丢的语音包 —— 这是唯一
     * 能客观区分「网络丢包」和「解码失败」的指标，比「感觉有杂音」可靠。
     * 只做统计，不参与任何控制逻辑，出问题也不会影响通联。 */
    /** 上一个 DMRD 的序号；-1 表示本会话还没收到过 */
    private volatile int dmrdSeqLast = -1;
    /** 收到的 DMRD 语音包总数 */
    private volatile long dmrdRxCount = 0;
    /** 由序号跳变推算出的丢包数 */
    private volatile long dmrdLostCount = 0;
    /** 序号往回跳（乱序或主站重发）的次数 */
    private volatile long dmrdReorderCount = 0;

    /* ---- 诊断计数：排查「一直正在登录」全靠这几个数 ---- */
    private volatile int txPackets;
    private volatile int rxPackets;
    private volatile int rptlSent;
    private volatile boolean gotAnyReply;

    /* ---- 跨进程的「上次可能还挂着会话」标记 ----
     *
     * 这个类拿不到 Context（它是纯 java.net，桌面自检也能跑），所以由
     * DmrEngine 在构造时从这里读、在状态变化时往这里写，真正落盘在那边。
     *
     * ★ 为什么必须有它：{@link #hadSession} / {@link #lastDisconnectMs} 都是
     * 内存变量。**进程被系统杀掉**（切后台内存紧张、用户从最近任务划掉、
     * 崩溃）时不会走 disconnect()，RPTCL 发不出去，主站那边的会话要等
     * 15~60 秒才回收。而重开 App 时 hadSession=false、lastDisconnectMs=0，
     * 我们完全不知道有残留，直接发 RPTL —— 必然被 MSTNAK。
     * 用户看到的就是「偶尔一打开就连不上，过一会儿自己好了」。
     */
    /** 上次进程退出时，主站那边可能还挂着本 ID 的会话。 */
    public static volatile boolean carrySession;
    /** 那个会话最后一次确认存在过的时刻（ms）。用来算还要等多久。 */
    public static volatile long carrySinceMs;
    /** 主站回收会话需要的时间。等满这个数再登录，比撞上去再重试快得多。 */

    /* ---- MSTNAK（主站拒绝）处理 ---- */
    private volatile int nakCount;
    private volatile long nakWaitUntil;
    private volatile long handshakeDeadline;
    /** 上次断开的时间。主站的会话不会立刻释放，紧接着重连会被 MSTNAK */
    private volatile long lastDisconnectMs;
    /** 最近一次收到主站报文的时间，用来判断握手是否停滞 */
    private volatile long lastProgressMs;
    /**
     * 本轮是否真的登录成功过。
     *
     * <p>只有登录成功过，主站才真的建立了会话，断开后才有「会话没释放」的问题。
     * 从头到尾没连上过的失败尝试，再次点击连接不需要等任何窗口。
     */
    private volatile boolean hadSession;

    /**
     * 本次运行中最近一次<b>真正登录成功</b>的主站与登录 ID。
     *
     * <p>用途：当一台主站连得上、另一台连不上时，这一条能把排查范围
     * 直接压到「ID / 呼号 / 主站登记」——协议、声码器、端口绑定、
     * 配置串构造这些「本机侧」因素可以一次性排除掉。
     */
    private volatile String lastOkHost = "";
    private volatile int lastOkLoginId = 0;
    /**
     * 握手阶段发出去的最后两个包，留着停滞时<b>原样重发</b>。
     *
     * <p>MMDVMHost 的重试是分状态的：停在 `WAITING_AUTHORISATION` 就重发
     * `writeAuthorisation()`（RPTK），停在 `WAITING_CONFIG` 就重发
     * `writeConfig()`（RPTC）—— <b>都不退回 RPTL</b>。
     * 退回 RPTL 等于告诉主站「同 ID 又登录了一次」，主站直接回 MSTNAK，
     * 自己把路堵死。
     */
    private volatile byte[] lastRptk;
    private volatile byte[] lastRptc;
    /**
     * 需要重建 UDP 套接字（<b>端口保持不变</b>）再重试。
     *
     * <p>只在「连发好几个 RPTL 却一个回包都没收到」时置位 ——
     * 那说明本地套接字或 NAT 映射出了问题。
     * <b>收到 MSTNAK 时绝不能置位</b>：主站按「IP + 端口」认热点，
     * 换端口等于冒出一台新热点，正好踩中「一个 Radio ID 只允许一台热点在线」。
     */
    private volatile boolean needSocketRestart;
    /**
     * 上一次 MSTNAK 时我们处在哪一步。
     *
     * <p>这是整个排障里最有用的一条信息：
     * `CONNECTING` = 连密码摘要都没发出去就被拒 → 只可能是「这个 ID 已经有会话」；
     * `AUTH` = 密码摘要不对；`CONF` = 呼号/配置被拒；`CONNECTED` = 被顶掉。
     * 显示在倒计时里，用户截个图就能定位。
     */
    private volatile String lastNakStage = "?";
    /** 已自动重连次数（MSTCL 触发），由用户手动连接时清零 */
    private volatile int autoReconnects;
    /**
     * 会话代数。用户每按一次「断开」就 +1。
     *
     * <p>用来取消已经排好队的自动重连：线程醒来时若代数变了，
     * 说明用户主动断开了，就不该再自己连回去。
     */
    private volatile int sessionGen;

    public MmdvmSession(Listener listener) {
        this.listener = listener;
    }

    public State getState() {
        return state;
    }

    /** 本机 Radio ID（裸 7 位）。DMRD 的 srcId 用这个 */
    public int getDmrId() {
        return dmrId;
    }

    /** 热点别名 ID（ESSID），0 表示未使用别名 */
    public int getEssid() {
        return essid;
    }

    /**
     * 登录用的 ID（含 ESSID）。
     *
     * <p>DMRD 业务包的 repeaterId 字段（{@code buffer[11..14]}）要用这个 ——
     * 它标识「是哪台热点中继的」，主站据此把通联记录归到正确的那台热点上。
     */
    public int getLoginId() {
        return loginId;
    }

    public int getPongCount() {
        return pongCount;
    }

    /**
     * 接收质量摘要（人类可读）。
     *
     * <p>丢包率 = 丢 / (收 + 丢)。这是**网络侧**的丢包，和「解码失败」「音量太小」
     * 是两回事 —— 分开看才能判断到底该调网络还是调音频。
     */
    public String getRxStats() {
        long rx = dmrdRxCount, lost = dmrdLostCount;
        long total = rx + lost;
        if (total <= 0) return "尚未收到语音包";
        return String.format(Locale.US, "收 %d 包 · 丢 %d 包（%.1f%%）", rx, lost, lost * 100.0 / total);
    }

    /** 丢包率百分比；本会话还没收到任何语音包时返回 -1 */
    public double getRxLossPercent() {
        long total = dmrdRxCount + dmrdLostCount;
        return total <= 0 ? -1 : dmrdLostCount * 100.0 / total;
    }

    /** 收到的语音包总数 */
    public long getRxPacketCount() {
        return dmrdRxCount;
    }

    /**
     * 会话收尾时把本轮接收质量打进日志。
     *
     * <p>只在真收到过语音包时才打 —— 一次都没收到的话这行没有信息量，
     * 反而会把有用的日志顶上去。
     */
    private void logRxStats(String why) {
        if (dmrdRxCount <= 0 && dmrdLostCount <= 0) return;
        log("本轮接收质量（" + why + "）：" + getRxStats()
                + (dmrdReorderCount > 0 ? " · 乱序 " + dmrdReorderCount + " 次" : ""));
    }

    /**
     * 本次会话「已建立之后」收到的 MSTNAK 条数。
     *
     * <p>这些都被有意忽略了（见 handle 里的说明），但数值本身是排障依据：
     * 数值持续增长说明我们每次重连都在制造重复 RPTL，该查重连策略；
     * 数值为 0 而链路却断了，说明是 MSTPONG 超时那条路发现的。
     */
    public int getLateNakCount() {
        return lateNakCount;
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    public void configure(String host, int port, int dmrId, int essid, String password) {
        this.host = host == null ? "" : host.trim();
        this.port = port > 0 ? port : DEFAULT_PORT;
        this.dmrId = dmrId;

        /* ESSID / 别名 ID：01~99 有效，0 或不填表示用裸 DMR ID 登录。
         * 超出范围按「没填」处理而不是报错 —— 这个字段填错不该阻塞连接，
         * 用户多半只是想用默认值。 */
        int ess = essid;
        if (ess < 0 || ess > 99) {
            log("ESSID 只支持 01~99，收到 " + essid + "，本次按「不使用别名」处理");
            ess = 0;
        }
        this.essid = ess;
        /* 映射和 DroidStar 保持一致（1-based）：它 dmr.cpp 里写的是
         * `m_dmrid*100 + (essid-1)`，UI 选 04 实际登录 461418703。
         * 按 MMDVM 教科书的 0-based 算会得到 461418704 —— 设备库里没有这条，
         * 主站就在 CONF 阶段回 MSTNAK，表现成「同样的配置它连得上、我们连不上」。
         * dmrId 最大 16777215，×100 后仍在 int 范围内，不会溢出。 */
        this.loginId = ess > 0 ? dmrId * 100 + (ess - 1) : dmrId;
        if (ess > 0) {
            log("使用热点别名 ID（ESSID）：" + String.format(Locale.US, "%02d", ess)
                    + "，登录 ID = " + loginId + "（本机 Radio ID 仍是 " + dmrId
                    + "，呼号与业务包不受影响）");
        }

        /* ⚠ 热点安全密码必须「逐字节」一致，而且官方 FAQ 专门警告过：
         * 「You may need to retype it manually or observe if you have accidentally
         *   copied a space at the beginning or end of the password when you copied
         *   and pasted it.」
         * 复制粘贴带上的首尾空格/换行是最常见、最难自己发现的错误。
         * 密码本身不会以空格开头或结尾，所以这里直接去掉并明确告知。 */
        String pwd = password == null ? "" : password;
        String trimmed = pwd.trim();
        if (!trimmed.equals(pwd)) {
            log("注意：密码首尾有空白字符（" + pwd.length() + " → " + trimmed.length()
                    + " 字符），已自动去掉 —— 通常是复制粘贴带进来的，"
                    + "请确认密码本身没有空格");
        }
        this.password = trimmed;
    }

    /**
     * 当前热点安全密码的指纹：SHA-256 的前 8 位十六进制。
     *
     * <p>日志里不能写明文密码，但排障时又必须回答「密码有没有变」。
     * 指纹能回答：两次登录指纹相同 → 密码一模一样，问题不在密码。
     * 空密码返回 {@code "(空)"}，一眼就能看出来。
     */
    private String pwdFingerprint() {
        if (password == null || password.isEmpty()) return "(空)";
        byte[] d = sha256(password.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 4; i++) sb.append(String.format(Locale.US, "%02x", d[i] & 0xFF));
        return sb.toString();
    }

    /**
     * softwareId 必须是合法的 8 位日期（YYYYMMDD）。主站在 CONF 阶段校验格式，
     * 不合格就回 MSTNAK —— 表现成「密码对了、呼号对了，就是连不上」。
     * 这里兜一道，任何调用方传进来的非法值都替换成默认值，并写日志说明。
     */
    private String sanitizeSoftwareId(String s) {
        String t = s == null ? "" : s.trim();
        if (t.matches("\\d{8}")) {
            try {
                int y = Integer.parseInt(t.substring(0, 4));
                int m = Integer.parseInt(t.substring(4, 6));
                int d = Integer.parseInt(t.substring(6, 8));
                if (y >= 2000 && y <= 2100 && m >= 1 && m <= 12 && d >= 1 && d <= 31) return t;
            } catch (NumberFormatException ignored) { }
        }
        log("⚠ 软件标识 \"" + t + "\" 不是合法日期（YYYYMMDD），"
                + "BrandMeister 会因此在配置阶段拒绝登录 —— 已改用 " + DEFAULT_SWID);
        return DEFAULT_SWID;
    }

    /**
     * packageId 必须以 {@code MMDVM} 开头，主站据此判断这是不是 MMDVM 系热点。
     * 非 MMDVM 值（含我们早期的 "BeaconDMR_Android"）会被 CONF 阶段拒绝。
     */
    private String sanitizePackageId(String s) {
        String t = s == null ? "" : s.trim();
        if (t.startsWith("MMDVM")) return t;
        log("⚠ 硬件标识 \"" + t + "\" 不以 MMDVM 开头，"
                + "BrandMeister 会因此在配置阶段拒绝登录 —— 已改用 " + DEFAULT_PKGID);
        return DEFAULT_PKGID;
    }

    /** 设置 RPTC 上报的静态信息，只影响主站侧显示，不影响通联 */
    public void setStationInfo(String callsign, String location, String description,
                               String url, String softwareId, long freqHz) {
        if (callsign != null) this.callsign = callsign;
        if (location != null) this.location = location;
        if (description != null) this.description = description;
        if (url != null) this.url = url;
        if (softwareId != null) this.softwareId = sanitizeSoftwareId(softwareId);
        if (freqHz > 0) this.freqHz = freqHz;
    }

    /* ================================================================ */
    /* 连接                                                             */
    /* ================================================================ */

    /** 用户点击「连接主站」。会清掉自动重连的计数。 */
    public void connect() {
        connectInternal(false);
    }

    /**
     * @param auto true 表示这是 MSTCL 之后的自动重连，不清零 autoReconnects
     */
    private void connectInternal(boolean auto) {
        if (!auto) autoReconnects = 0;
        if (running) {
            log("已经在连接中，忽略重复请求");
            return;
        }
        if (host.isEmpty()) {
            fail("主站地址为空");
            return;
        }
        if (dmrId <= 1000) {
            fail("DMR ID 无效：" + dmrId);
            return;
        }
        /* 校验完 dmrId 再算一次 loginId，保证 configure 与 connect 之间
         * 即使有人改过字段也一致。映射同 configure()：1-based，对齐 DroidStar。 */
        loginId = essid > 0 ? dmrId * 100 + (essid - 1) : dmrId;
        if (password.isEmpty()) {
            fail("热点密码为空");
            return;
        }

        running = true;
        pongCount = 0;
        lastPongMs = 0;
        pingsSincePong = 0;
        lateNakCount = 0;
        txPackets = 0;
        rxPackets = 0;
        /* 丢包统计必须跟着会话一起清零：dmrdSeqLast 若留着上一轮的序号，
         * 新会话第一个包会被算成「跳了 N 个」，凭空多出一堆丢包。 */
        dmrdSeqLast = -1;
        dmrdRxCount = 0;
        dmrdLostCount = 0;
        dmrdReorderCount = 0;
        rptlSent = 0;
        gotAnyReply = false;
        nakCount = 0;
        lastProgressMs = System.currentTimeMillis();
        lastRptk = null;
        lastRptc = null;
        needSocketRestart = false;
        lastNakStage = "?";

        /* 上一次是否真的登录成功过。只有成功过，主站才可能还留着旧会话。 */
        boolean hadPrevSession = hadSession;
        hadSession = false;

        /* ① 同一次运行内：刚断开不久就重连，先给 RPTCL 一点生效时间。 */
        long since = System.currentTimeMillis() - lastDisconnectMs;
        if (hadPrevSession && lastDisconnectMs > 0 && since < POST_DISCONNECT_WAIT_MS) {
            long wait = POST_DISCONNECT_WAIT_MS - since;
            nakWaitUntil = System.currentTimeMillis() + wait;
            log("距上次断开仅 " + (since / 1000) + " 秒，主站会话可能还没释放，"
                    + "先等 " + wait + " 毫秒再登录");
        } else {
            nakWaitUntil = 0L;
        }

        /* ② 跨进程：上次是被杀掉的，主站那边**可能**还挂着会话。
         *
         * ★ v2.6.2 撤掉了这里原来的「先等满 20 秒再发第一个包」。
         *
         * 那是我上一版（v2.5.8）的判断，方向错了：它把「偶尔会撞车」当成了
         * 「一定会撞车」，于是**每次**冷启动都要白等 20 秒。而实际情况是
         * 大多数时候主站早就回收了，第一次发 RPTL 就能过。
         * 用户的原话：「bm 的第一次要等很久，体验不好」—— 就是我加的那 20 秒。
         *
         * 现在的策略回到<b>乐观首发</b>：
         *   · 直接发 RPTL。成功就是成功，一条路都不用多走；
         *   · 真被拒（MSTNAK）才进重试链，首档 15 秒正是主站 hand-off timer。
         *
         * 代价是撞车时要多绕一轮 15 秒；收益是绝大多数情况下省掉 20 秒。
         * 「偶尔多等 15 秒」比「每次多等 20 秒」划算得多。
         *
         * carrySession 保留下来做一件小事：被拒时知道「这大概率是上次没断干净」，
         * 文案里就能直接说清楚，而不是让用户去猜密码。 */
        if (carrySession) {
            long gap = carrySinceMs > 0 ? System.currentTimeMillis() - carrySinceMs : -1;
            log("上次没正常断开" + (gap >= 0 ? "（距今 " + (gap / 1000) + " 秒）" : "")
                    + "，主站可能还留着会话；如果这次被拒，多半就是这个原因");
            carrySession = false;
        }
        handshakeDeadline = Math.max(nakWaitUntil, System.currentTimeMillis())
                + HANDSHAKE_TIMEOUT_MS;

        /* 解析全部地址，不只取第一条 —— 主站通常有多条 A 记录，
         * 只试第一条的话，遇到一条不通的就永远卡在登录。 */
        try {
            masters = InetAddress.getAllByName(host);
        } catch (IOException e) {
            running = false;
            fail("域名解析失败：" + host + "\n"
                    + "这个地址不存在，或者当前网络解析不了域名。\n"
                    + "• 地址规则是「<主站ID>.master.brandmeister.network」，"
                    + "主站 ID 取自官方主站表 api.brandmeister.network/v2/master。\n"
                    + "• 中国区目前只有 4602 一台，即 4602.master.brandmeister.network。"
                    + "备选：4501（韩国）、5021（马来西亚）、5051（澳大利亚）。\n"
                    + "• 4601.master.brandmeister.network 与裸的 master.brandmeister.network "
                    + "都不存在，填了必然解析失败。\n"
                    + "• 如果只是本机 DNS 被劫持，可以直接填 IP（如 43.129.83.124）——"
                    + "IP 不需要解析，原样使用。");
            return;
        }
        if (masters.length == 0) {
            running = false;
            fail("域名解析没有返回任何地址：" + host);
            return;
        }
        masterIdx = 0;
        master = masters[0];

        /* 保险：万一还有旧套接字没关掉，先关掉 ——
         * 同一个 UDP 端口不能重复 bind，不关就绑不上固定端口。 */
        DatagramSocket stale = socket;
        socket = null;
        if (stale != null) {
            try {
                stale.close();
            } catch (Exception ignored) {
            }
        }

        try {
            DatagramSocket s = openSocket();
            socket = s;
            log("本机 UDP 端口 " + s.getLocalPort()
                    + "（本次运行固定用这一个端口 —— 主站按「IP + 端口」认热点，"
                    + "端口一变就会被当成另一台热点）");
        } catch (IOException e) {
            running = false;
            fail("无法创建 UDP 套接字：" + e.getMessage());
            return;
        }

        StringBuilder ips = new StringBuilder();
        for (InetAddress a : masters) {
            if (ips.length() > 0) ips.append(", ");
            ips.append(a.getHostAddress());
        }
        log("域名 " + host + " 解析到 " + masters.length + " 个地址：" + ips);
        log("目标主站 " + master.getHostAddress() + ":" + port + "（UDP），登录 ID " + loginId
                + (essid > 0 ? "（本机 Radio ID " + dmrId + " + 别名 "
                        + String.format(Locale.US, "%02d", essid) + "）" : ""));

        rxThread = new Thread(this::rxLoop, "mmdvm-rx");
        rxThread.setDaemon(true);
        rxThread.start();

        pingThread = new Thread(this::pingLoop, "mmdvm-ping");
        pingThread.setDaemon(true);
        pingThread.start();

        setState(State.CONNECTING, "已发送 RPTL，等待主站应答");

        ensureHandshakeThread();
    }

    /**
     * 确保握手重试线程在跑。
     *
     * <p><b>这个方法必须存在。</b>握手线程在进入 {@link State#CONNECTED} 时会退出
     * （`while (… && state != CONNECTED …)`）。此时若主站又发来 MSTNAK
     * （比如被同一 Radio ID 的另一个登录顶掉），状态会被改回 CONNECTING 并
     * 显示「N 秒后自动重试」—— 但**已经没有任何线程负责重试了**，
     * 界面就会永远停在那一行，看起来像卡死。
     *
     * <p>所以凡是「把状态推回 CONNECTING」的地方，都必须调一次本方法。
     */
    private void ensureHandshakeThread() {
        /* 加锁：rx 线程和 ping 线程都会调这里，并发下可能起出两个握手线程，
         * 两个都发 RPTL 就成了「同 ID 重复登录」，反而招来 MSTNAK。 */
        synchronized (hsLock) {
            Thread t = hsThread;
            if (t != null && t.isAlive()) return;
            hsThread = new Thread(this::handshakeLoop, "mmdvm-hs");
            hsThread.setDaemon(true);
            hsThread.start();
        }
    }

    /**
     * 握手重试与超时看护。
     *
     * <p>主站或中间网络偶尔会吞掉第一个 RPTL（UDP 无重传），所以必须自己重发。
     * 若一直收不到任何应答，就换一个解析出来的地址再试；全都不行就报错，
     * 并把「发了几个包、收了几个包」写进日志 —— 这是区分
     * 「网络被阻断」与「协议/凭据不对」的唯一依据。
     */
    private void handshakeLoop() {
        final byte[] rptl = new byte[8];
        rptl[0] = 'R'; rptl[1] = 'P'; rptl[2] = 'T'; rptl[3] = 'L';
        /* 用 loginId 而不是 dmrId：带 ESSID 时它是 461418701 这样的 9 位 ID，
         * 主站据此认出这是「同一呼号下的另一台热点」而不是抢别人的会话。 */
        putBE32(rptl, 4, loginId);

        while (running && state != State.CONNECTED && state != State.ERROR) {
            /* ① 刚被主站拒绝（MSTNAK）：等 hand-off 窗口过去，这期间一个包都不发。
             *    继续发只会不断刷新拒绝，反而更慢。 */
            long wait = nakWaitUntil - System.currentTimeMillis();
            if (wait > 0) {
                /* 每秒把剩余秒数刷到界面上。数字在动，用户就知道是「在等」
                 * 而不是「卡死」—— 这正是上一版被误判为卡住的地方。
                 * 这里走 setStateQuiet：不写日志，否则 38 秒会刷出 38 行噪声。 */
                int sec = (int) ((wait + 999) / 1000);
                String what;
                if (nakCount > 0) {
                    /* 把「用哪个 ID 登录」「卡在哪一步」都写进状态行 ——
                     * 用户截一张图就能定位，不必再翻日志。
                     * 卡在 CONNECTING（连密码摘要都还没发出去）几乎一定是
                     * 「这个 ID 已经有会话」，而唯一的解法是换一个 ESSID 别名。 */
                    what = "主站拒绝登录 ID " + loginId
                            + (essid > 0
                                ? "（别名 " + String.format(Locale.US, "%02d", essid) + "）"
                                : "（未用别名）")
                            + "，停在 " + lastNakStage + " 步，第 "
                            + nakCount + "/" + (MAX_NAK - 1) + " 次重试，倒计时 ";
                } else {
                    what = "刚断开过，等主站释放旧会话，倒计时 ";
                }
                setStateQuiet(State.CONNECTING, what + sec + " 秒…");
                if (!sleepQuiet(Math.min(wait, 1000))) return;
                if (expired()) { timeoutFail(); return; }
                continue;
            }

            /* ② 握手已经在推进（AUTH / CONF）：绝对不能再插 RPTL。
             *    重复发 RPTL 会让主站看到同 ID 的第二次登录，直接回 MSTNAK ——
             *    这正是「要点很多次才能连上」的成因之一。
             *    但也不能干等：UDP 会丢包，RPTK / RPTC 丢了就永远停在原地。
             *    所以停滞超过 STALL_MS 就**原样重发当前阶段那个包**，
             *    对齐 MMDVMHost 的 writeAuthorisation() / writeConfig()。 */
            if (state != State.CONNECTING) {
                if (!sleepQuiet(200)) return;
                if (expired()) { timeoutFail(); return; }
                if (System.currentTimeMillis() - lastProgressMs > STALL_MS) {
                    lastProgressMs = System.currentTimeMillis();
                    if (state == State.AUTH && lastRptk != null) {
                        log("握手停滞在 AUTH 超过 " + (STALL_MS / 1000) + " 秒，重发 RPTK（不退回 RPTL）");
                        send(lastRptk);
                    } else if (state == State.CONF && lastRptc != null) {
                        log("握手停滞在 CONF 超过 " + (STALL_MS / 1000) + " 秒，重发 RPTC（不退回 RPTL）");
                        send(lastRptc);
                    } else {
                        /* 兜底：没有可重发的包（理论上不会走到），只能从头来 */
                        log("握手停滞在 " + state + " 且无可重发的包，退回重新登录");
                        setState(State.CONNECTING, "握手停滞，重新登录");
                    }
                }
                continue;
            }

            /* ③ 重建套接字只在「完全没有回音」时做，而且端口保持不变。
             *    收到过 MSTNAK 说明链路是通的 —— 此时换端口有百害而无一利，
             *    只会把自己变成主站眼里的「另一台热点」。 */
            if (needSocketRestart) {
                needSocketRestart = false;
                if (!restartSocket()) return;
            }

            /* ④ 连试几轮仍无任何回音 → 换一个解析出来的地址；
             *    所有地址都试过还是没回音 → 重建套接字（端口仍不变）。 */
            if (rptlSent > 0 && !gotAnyReply) {
                if (masters.length > 1 && rptlSent % 2 == 0) {
                    masterIdx = (masterIdx + 1) % masters.length;
                    master = masters[masterIdx];
                    log("该地址无回音，换用 " + master.getHostAddress());
                } else if (rptlSent >= 4 && rptlSent % 4 == 0) {
                    log("连发 " + rptlSent + " 个 RPTL 一个回包都没收到，重建套接字再试");
                    needSocketRestart = true;
                }
            }

            send(rptl);
            rptlSent++;
            if (rptlSent > 1) log("重发 RPTL（第 " + rptlSent + " 次）");

            if (!sleepQuiet(HANDSHAKE_RETRY_MS)) return;
            if (expired()) { timeoutFail(); return; }
        }
    }

    /** 可被打断的睡眠，返回 false 表示已要求停止 */
    private boolean sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /**
     * 打开 UDP 套接字，并且<b>始终绑定同一个本地端口</b>。
     *
     * <p>主站按「IP + 源端口」认热点。真实热点（Pi-Star / MMDVMHost）在
     * MMDVM.ini 里写死 `Local=62031`，永远不换端口；我们必须照做，
     * 否则每次重试在主站眼里都是一台「新热点」，同一 Radio ID 下冒出
     * 一堆幽灵热点，主站只能按「一个 ID 只允许一台热点在线」回 MSTNAK。
     *
     * <p>所以：优先 62031；被别的程序占了就往后顺延试；
     * 实在都不行才退回临时端口，但那个临时端口也<b>只在本次运行内取一次</b>，
     * 之后所有重连都复用它。
     */
    /**
     * UDP 收发缓冲目标值。
     *
     * <p>Android 的 {@link DatagramSocket} 默认接收缓冲往往只有 64 KB 甚至更小。
     * DMR 语音是每 60 ms 一个 55 字节包，看着不密集，但手机在蜂窝网下
     * 会成串到达（基站的调度抖动），一个 200 ms 的突发就是 3~4 个包，
     * 叠加系统后台活动的停顿，默认缓冲很容易在 {@code receive()} 之前就被覆盖 ——
     * 表现就是「偶尔丢一两个字」。
     *
     * <p>调到 512 KB 后，即使接收线程被调度器推迟几百毫秒，包也还在内核队列里。
     * 设完会读回真实值写日志 —— 部分 ROM 会把值夹到内核上限，光设不报等于没验证。
     */
    private static final int RCVBUF_BYTES = 512 * 1024;
    private static final int SNDBUF_BYTES = 256 * 1024;

    /** 给新建的套接字套上缓冲调优，并记录真实生效值 */
    private DatagramSocket tune(DatagramSocket s) {
        int wantR = 0, wantS = 0, gotR = 0, gotS = 0;
        try { s.setReceiveBufferSize(RCVBUF_BYTES); wantR = 1; } catch (Exception ignored) { }
        try { s.setSendBufferSize(SNDBUF_BYTES); wantS = 1; } catch (Exception ignored) { }
        try { gotR = s.getReceiveBufferSize(); } catch (Exception ignored) { }
        try { gotS = s.getSendBufferSize(); } catch (Exception ignored) { }
        if (wantR == 1 || wantS == 1) {
            log("UDP 缓冲：收 " + (gotR / 1024) + " KB · 发 " + (gotS / 1024) + " KB"
                    + "（申请 " + (RCVBUF_BYTES / 1024) + "/" + (SNDBUF_BYTES / 1024) + " KB）");
        }
        return s;
    }

    private DatagramSocket openSocket() throws IOException {
        if (localPort != 0) {
            try {
                DatagramSocket s = new DatagramSocket(localPort);
                s.setSoTimeout(RECV_TIMEOUT_MS);
                return tune(s);
            } catch (IOException e) {
                /* 端口暂时被占（例如上一次的套接字还没完全释放）。
                 * 不换端口，等一小会儿再试一次 —— 换端口正是我们要避免的事。 */
                log("本地端口 " + localPort + " 暂时占用（" + e.getMessage() + "），稍等后重试同一端口");
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
                try {
                    DatagramSocket s = new DatagramSocket(localPort);
                    s.setSoTimeout(RECV_TIMEOUT_MS);
                    return tune(s);
                } catch (IOException e2) {
                    log("本地端口 " + localPort + " 仍不可用，只好另选端口");
                    localPort = 0;
                }
            }
        }

        for (int i = 0; i < LOCAL_PORT_TRIES; i++) {
            int p = LOCAL_PORT_PREFERRED + i;
            try {
                DatagramSocket s = new DatagramSocket(p);
                s.setSoTimeout(RECV_TIMEOUT_MS);
                localPort = p;
                return tune(s);
            } catch (IOException ignored) {
                /* 该端口被占，试下一个 */
            }
        }

        DatagramSocket s = new DatagramSocket();
        s.setSoTimeout(RECV_TIMEOUT_MS);
        localPort = s.getLocalPort();
        log("注意：62031 起连续 " + LOCAL_PORT_TRIES + " 个端口都被占用，"
                + "改用临时端口 " + localPort + "（本次运行内仍保持固定）");
        return tune(s);
    }

    /**
     * 重建 UDP 套接字，<b>但保持本地端口不变</b>。
     *
     * <p>只用于「连发好几个 RPTL 却一个回包都没收到」这种本地套接字
     * 或 NAT 映射出问题的情况。**绝不能因为收到 MSTNAK 就重建套接字** ——
     * 那样等于主动把自己变成一台新热点，正好踩中主站的
     * 「一个 Radio ID 只允许一台热点在线」判定，越重试越连不上。
     *
     * <p>只由握手线程调用（rxLoop 会自己发现套接字被换掉并接着读新套接字）。
     */
    private boolean restartSocket() {
        DatagramSocket old = socket;

        /* 必须先摘引用、再关旧套接字、最后绑新套接字，顺序不能错：
         * ① 先摘引用（置 null），让 rxLoop 知道「正在换套接字」，
         *    不至于把旧套接字被 close 抛出的异常当成致命错误而退出线程；
         * ② 旧套接字必须真的关掉才能重新绑同一个端口 ——
         *    同一个 UDP 端口不能重复 bind，不关就绑不上，
         *    openSocket() 会以为端口被占而换端口，正好破坏我们想保住的东西。 */
        socket = null;
        if (old != null) {
            try {
                old.close();
            } catch (Exception ignored) {
            }
        }

        DatagramSocket fresh;
        try {
            fresh = openSocket();
        } catch (IOException e) {
            log("重建 UDP 套接字失败：" + e.getMessage());
            shutdownAfterError();
            fail("无法重建网络连接：" + e.getMessage());
            return false;
        }

        socket = fresh;
        log("已重建 UDP 套接字，本地端口仍是 " + fresh.getLocalPort() + "（保持不变）");
        return true;
    }

    private boolean expired() {
        return System.currentTimeMillis() > handshakeDeadline;
    }

    private void timeoutFail() {
        /* 必须先把 running 放掉，否则用户不按「断开」就再也连不上
         * （connect() 会因为 running==true 直接返回） */
        shutdownAfterError();
        fail(handshakeDiagnosis());
    }

    /** 握手超时后给出的可执行诊断 */
    private String handshakeDiagnosis() {
        StringBuilder sb = new StringBuilder();
        sb.append("登录失败：已发 ").append(txPackets).append(" 个包、发 RPTL ")
          .append(rptlSent).append(" 次，收到主站报文 ").append(rxPackets).append(" 个。");
        sb.append(" 登录 ID ").append(loginId);
        if (essid > 0) {
            sb.append("（Radio ID ").append(dmrId).append(" + ESSID 别名 ")
              .append(String.format(Locale.US, "%02d", essid)).append("）");
        }
        sb.append("。");

        if (rxPackets == 0) {
            sb.append(" 主站完全无应答 —— 这是网络层问题，不是密码问题。依次检查：")
              .append("① 换 Wi-Fi / 换移动数据再试（有些网络阻断 UDP 62031）；")
              .append("② 确认主站地址是 4602.master.brandmeister.network、端口 62031；")
              .append("③ 到 brandmeister.network 的 Dashboard → Device Logs 看有没有登录记录 —— ")
              .append("一条都没有就说明包根本没到主站。");
        } else {
            sb.append(" 收到过主站报文但握手没走完，请把日志发回。");
        }
        return sb.toString();
    }

    public void disconnect() {
        /* 用户主动断开：代数 +1 让已经排队的自动重连作废。
         * 这一步必须在下面的提前返回之前做 —— 否则在主站刚关闭、
         * 自动重连还没触发的空档里按「断开」，它还是会自己连回来。 */
        sessionGen++;
        autoReconnects = 0;

        /* 用户主动断开前把本轮接收质量记一笔，方便事后回看通联效果 */
        logRxStats("用户断开");

        if (!running && socket == null) {
            setState(State.IDLE, "已断开");
            return;
        }

        // 礼貌关闭：RPTCL + id
        /* 只要主站可能已经为我们建了会话（AUTH 之后），就发登出。
         * 少发一个 RPTCL，会话就得靠主站的 hand-off 计时器回收，
         * 表现就是「断开后马上重连连不上」。 */
        if (state == State.CONNECTED || state == State.CONF || state == State.AUTH) {
            byte[] bye = new byte[9];
            bye[0] = 'R'; bye[1] = 'P'; bye[2] = 'T'; bye[3] = 'C'; bye[4] = 'L';
            putBE32(bye, 5, loginId);
            send(bye);
            /* 给 RPTCL 一点时间真的发出去再关套接字，
             * 否则主站可能收不到登出，会话要靠自己的计时器才释放 */
            sleepQuiet(200);
        }

        running = false;
        lastDisconnectMs = System.currentTimeMillis();

        DatagramSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }

        Thread t;
        t = rxThread; rxThread = null;
        if (t != null) t.interrupt();
        t = pingThread; pingThread = null;
        if (t != null) t.interrupt();
        t = hsThread; hsThread = null;
        if (t != null) t.interrupt();

        setState(State.IDLE, "已断开");
    }

    /* ================================================================ */
    /* 接收与握手                                                       */
    /* ================================================================ */

    private void rxLoop() {
        byte[] buf = new byte[RX_BUFFER];

        while (running) {
            DatagramSocket s = socket;
            if (s == null) {
                /* restartSocket() 换套接字的瞬间会短暂置空。
                 * 这不是「该退出了」——直接 break 会让接收线程死掉，
                 * 之后谁也收不到主站的包。稍等一下再看。 */
                if (!sleepQuiet(50)) break;
                continue;
            }

            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            try {
                s.receive(pkt);
            } catch (SocketTimeoutException e) {
                continue;                     // 正常，用来检查 running
            } catch (IOException e) {
                if (!running) break;
                /* 套接字被 restartSocket() 换掉了：旧的 close() 会让这里抛异常。
                 * 这不是错误，拿新套接字接着读就行。 */
                if (socket != s) continue;

                /* ★★ 同一个套接字抛 IOException —— **绝对不能 break**。
                 *
                 * 会走到这里的典型场景：Wi-Fi 与移动数据互切、NAT 映射被回收、
                 * 系统在低内存下回收了 fd、飞行模式开关一次。
                 * 早先这里直接 break，接收线程就此死掉，后果是连锁的：
                 *   · 主站的 MSTPONG 收不到 → 25 秒后判定「心跳超时」去重连
                 *   · 重连时如果 socket 是复用的，接收线程却已经是死的
                 *   · 于是界面显示「已连接」，但既没有语音也没有心跳 ——
                 *     只有杀进程重启才恢复
                 * 症状就是用户报的「有人正在通联但没声音，只能重启」和
                 * 「待机时莫名心跳超时重连」。
                 *
                 * 正确做法：重建套接字，继续循环。丢几个包远比线程死掉轻。 */
                log("接收异常：" + e.getMessage() + " —— 重建套接字后继续（接收线程不退出）");
                try {
                    restartSocket();
                } catch (Exception ignored) {
                }
                if (!sleepQuiet(200)) break;
                continue;
            }

            int len = pkt.getLength();
            if (len <= 0) continue;

            rxPackets++;
            gotAnyReply = true;
            lastProgressMs = System.currentTimeMillis();

            /* ⚠ 不按来源 IP 丢弃报文。
             * 上游 DroidStar / MMDVMHost 都不做这个过滤；主站可能有多条 A 记录，
             * 也可能从别的地址回包，过滤会把登录应答直接丢掉，
             * 表现就是「一直卡在正在登录」。这里只记一笔，照常处理。 */
            InetAddress from = pkt.getAddress();
            if (from != null) {
                boolean known = false;
                for (InetAddress a : masters) {
                    if (from.equals(a)) { known = true; break; }
                }
                if (!known) {
                    log("注意：报文来自非解析地址 " + from.getHostAddress() + "，仍按主站处理");
                }
            }

            /* 握手阶段的报文全部打出来，便于定位卡在哪一步 */
            if (state != State.CONNECTED) {
                log("← " + len + " 字节 [" + asciiOf(buf, len) + "] "
                        + hex(buf, Math.min(len, 24)) + (len > 24 ? " …" : ""));
            }

            try {
                handle(buf, len);
            } catch (Exception e) {
                log("处理报文出错：" + e);
            }
        }
    }

    private void handle(byte[] buf, int len) {
        if (startsWith(buf, len, "MSTNAK", 6)) {
            /* 记下被拒时我们处在哪一步 —— 这是区分「ID 被占」与「凭据不对」的唯一依据：
             *   CONNECTING → 连 RPTK 都还没发出去就被拒，只可能是「这个 ID 已经有会话」
             *   AUTH       → 密码摘要不对，或该 ID 没在 SelfCare 登记热点
             *   CONF       → 呼号/配置串被拒
             *   CONNECTED  → <b>不当作拒绝</b>：这是上一次尝试的延迟报文，直接忽略（见下） */
            State was = state;

            /* ★★ 会话已经建立（三次 RPTACK 全部走完）时收到的 MSTNAK，
             * **绝不能当成本次登录被拒**。
             *
             * 它几乎总是「上一次登录尝试」留下的延迟报文：握手阶段我们重发过 RPTL，
             * 主站把重复的那一次判成「同 ID 第二次登录」回了 MSTNAK，UDP 延迟到达，
             * 而此时我们早已握手完成、正连着。
             *
             * 早先这里一律当成「被同 ID 的另一台设备顶掉」→ 主动断开重连 →
             * 重连又发 RPTL → 又制造一个延迟 MSTNAK …… 形成
             * 「连上 → 发射一次 → 显示拒绝登录 → 重连」的死循环。
             *
             * 判断依据：主站真要踢掉一个**已建立**的会话，发的是 MSTCL，不是 MSTNAK。
             * 所以这里只记账、不动作；链路真死了由 pingLoop 的 MSTPONG 超时兜住。
             */
            if (was == State.CONNECTED) {
                lateNakCount++;
                log("收到一条延迟的 MSTNAK（会话已建立，多半是上一次登录尝试的重复 RPTL 引来的），"
                        + "已忽略第 " + lateNakCount + " 条 —— 主站真要踢掉已建立的会话会发 MSTCL");
                return;
            }

            /* 空密码在 AUTH 被拒：重试 5 次毫无意义，只会让主站把这条热点临时锁掉
             * （BrandMeister 对连续失败的凭据校验会限流，而且它不会告诉你被限流了）。
             * 直接停机，把「去哪儿填什么」说清楚。 */
            if (was == State.AUTH && (password == null || password.isEmpty())) {
                shutdownAfterError();
                log("热点安全密码是空的 —— 设置页里没填，主站不可能放行。\n"
                        + "去 BrandMeister SelfCare → SelfCare → Hotspot Security，"
                        + "按你的 Radio ID " + dmrId + " 设一个密码"
                        + "（20 字符以内、不含特殊字符），\n"
                        + "填进设置页的「热点安全密码」再连接。\n"
                        + "已停止重试 —— 空密码重试只会让主站把这条热点临时锁掉。");
                return;
            }

            nakCount++;
            if (nakCount >= MAX_NAK) {
                shutdownAfterError();
                StringBuilder sb = new StringBuilder();
                sb.append("主站连续 ").append(nakCount).append(" 次拒绝登录（MSTNAK），已停止重试。\n");
                sb.append("登录 ID：").append(loginId);
                if (essid > 0) {
                    sb.append("（Radio ID ").append(dmrId).append(" + 别名 ESSID ")
                      .append(String.format(Locale.US, "%02d", essid)).append("）");
                } else {
                    sb.append("（未使用别名 ESSID，就是裸 DMR ID）");
                }
                sb.append("\n本机全程用同一个 UDP 端口 ").append(localPort).append(" 登录。\n\n");

                if (was == State.CONNECTING) {
                    sb.append("被拒在「连密码摘要都还没发出去」这一步 —— 与密码无关，")
                      .append("只可能是这个登录 ID 已经有会话在线。\n");
                } else if (was == State.AUTH) {
                    sb.append("被拒在「密码校验」这一步 —— 热点安全密码不对，")
                      .append("或这个 ID 还没在 SelfCare 里设过热点密码。\n")
                      .append("注意：热点安全密码 ≠ SelfCare 登录密码；官方要求 20 字符以内、")
                      .append("不含特殊字符，且要按 Radio ID 逐个设置。\n");

                    /* ★ 如果这份凭据刚刚在本机通过过校验，那「密码不对」这个结论
                     * 就是错的 —— 必须当场说出来，否则用户会去改一个本来正确的密码，
                     * 白白浪费半小时，而且改完之后旧密码就真的对不上了。 */
                    long okAt = lastAuthOkAtMs;
                    String fp = pwdFingerprint();
                    boolean sameFp = fp.equals(lastAuthOkFp);
                    if (okAt > 0 && sameFp) {
                        long mins = (System.currentTimeMillis() - okAt) / 60000L;
                        if (mins <= 120) {
                            sb.append("\n★ 但这份密码在本机 ").append(mins)
                              .append(" 分钟前刚刚通过过校验（指纹 ").append(fp)
                              .append(" 完全相同）—— 所以问题多半不在密码本身。\n")
                              .append("  常见原因按可能性排序：\n")
                              .append("    1. 主站还挂着上一次的会话。进程被强杀（装更新、清后台）时")
                              .append("没有发出登出包，主站要等自己超时。等 2~3 分钟再连一次。")
                              .append("（v2.5.8 起本机会先自己等 20 秒、再按 15/20/30/45/60 秒重试，")
                              .append("不用手动点「连接」。）\n")
                              .append("    2. 短时间内失败次数太多，主站临时限流。停 10 分钟。\n")
                              .append("    3. 同一个 DMR ID 在别的设备/热点上在线，主站只允许一台。")
                              .append("去 SelfCare 的 Hotspots 页看有没有多余的在线记录。\n")
                              .append("    4. 公网 IP 变了（换网络/切运营商）。一般不影响，但 BM 偶尔会认旧 IP。\n")
                              .append("  在这几种情况下重设密码只会让情况更糟 —— 先别动密码。\n");
                        }
                    } else if (!sameFp && lastAuthOkAtMs > 0) {
                        sb.append("\n★ 密码指纹变了（上次通过时是 ").append(lastAuthOkFp)
                          .append("，这次是 ").append(fp).append("）—— 密码确实被改过或没填对。\n");
                    }
                } else if (was == State.CONF) {
                    sb.append("被拒在「下发配置」（RPTC）这一步 —— 密码已经通过了")
                      .append("（RPTK 换回了正常 RPTACK），所以不是密码问题，")
                      .append("而是主站核对这台热点的身份没通过。\n")
                      .append("这一步主站会拿两样东西去比对：\n")
                      .append("  · 登录 ID 是不是在 SelfCare 里登记过的那条热点记录；\n")
                      .append("  · RPTC 里上报的呼号，是不是和该 DMR ID 注册的呼号完全一致。\n")
                      .append("本次实际发出去的是：登录 ID ").append(loginId)
                      .append("，呼号 ").append(callsign.isEmpty() ? "（空 → 用 DMR ID 顶替）" : callsign)
                      .append("。上面 RPTC 日志已逐字段打印，可直接拿来对照。\n");
                    /* 本机刚在别的主站上连成功过 → 把「本机侧」因素一次性排除。
                     * 这是最有说服力的一条证据：同一份代码、同一个声码器、
                     * 同一个端口、同一个配置串构造，换台主站就连上了。 */
                    if (!lastOkHost.isEmpty() && !lastOkHost.equals(host)) {
                        sb.append("参考：本次运行中本机已成功登录过 ")
                          .append(lastOkHost).append("（登录 ID ").append(lastOkLoginId)
                          .append("）。这说明协议、声码器、端口绑定、配置串构造全都正常 —— ")
                          .append("这次的拒绝几乎只可能出在「ID / 呼号 / 主站登记」上。\n");
                    }
                    if (essid > 0) {
                        /* 这是实测出来的头号原因：Pi-Star / DroidStar 的 ESSID 默认是
                         * None，绝大多数人 SelfCare 里也只登记了裸 DMR ID 那一台。
                         * 我们这边一加别名，登录 ID 就变成 9 位，
                         * 主站查不到这条记录，必然在这一步拒绝。 */
                        sb.append("⚠ 你现在用的是别名 ID ").append(loginId)
                          .append("（= ").append(dmrId).append(" + ESSID ")
                          .append(String.format(Locale.US, "%02d", essid)).append("）。")
                          .append("如果 SelfCare 里只登记了裸 ID ").append(dmrId)
                          .append("，这条 9 位记录并不存在，主站必然在这一步拒绝 —— ")
                          .append("这种情况要把「ESSID 别名 ID」改回 0（不用别名）。\n");
                    } else {
                        sb.append("你现在用的是裸 DMR ID ").append(loginId)
                          .append("（未用别名）。如果另一台客户端必须用 9 位别名 ID 才连得上，")
                          .append("说明主站给你分配的是别名那条记录，这时才需要把 ESSID 设成和它一样。\n");
                    }
                } else {
                    sb.append("我们本来是已连接状态，被主站踢了下来 —— 多半是同一个登录 ID ")
                      .append("在别处又登录了一次。\n");
                }

                sb.append("\n请按顺序查（第①条先做，最省事也最常命中）：\n")
                  .append("① 拿一台「现在能连上」的客户端（Pi-Star / DroidStar / BlueDV）")
                  .append("对照它的 ESSID 那一项：\n")
                  .append("   · 它显示 None / 0 → 说明你名下登记的就是裸 DMR ID。")
                  .append("本 App 的「ESSID 别名 ID」也要选 0（不用别名），")
                  .append("登录 ID 应该是 7 位。⚠ 先关掉那台，同一个 ID 不能两台同时在线。\n")
                  .append("   · 它显示 01~99 → 把本 App 的 ESSID 设成同一个值；")
                  .append("或者换一个它没用过的号（02、03…），让两台各占一条记录。\n")
                  .append("   依据：主站按「ID + 别名」认设备，SelfCare 里没登记的那条记录")
                  .append("连密码都校验不了，配置阶段必然被拒。\n")
                  .append("② 核对呼号：必须和这个 DMR ID 注册的呼号完全一致")
                  .append("（大小写、数字 0 与字母 O、数字 1 与字母 l 最容易错）。")
                  .append("呼号留空时 App 只能用 DMR ID 顶替，发上去是一串数字，必被判不匹配。")
                  .append("最快的核对办法：把「现在能连上」那台客户端里填的呼号原样抄过来；")
                  .append("或去 radioid.net 按 DMR ID 查注册呼号。\n")
                  .append("③ Dashboard → Device Logs，选中这个 ID —— ")
                  .append("主站会直接写明拒绝原因，这是最权威的一手信息。\n")
                  .append("④ Dashboard → Hotspots：看这个 ID 现在是否显示为在线。")
                  .append("在线就说明有另一台热点还开着（Pi-Star / openSPOT / BlueDV 等）。")
                  .append("Device 页面还会记录这个 ID 上次用过的设备型号，")
                  .append("如果那不是本 App，占着会话的就是它。\n")
                  .append("⑤ 换个主站试：地址改成 4501.master.brandmeister.network（韩国）")
                  .append("或 5021（马来西亚）。会话挂在某一台主站上，换一台有时能进。\n")
                  .append("⑥ 到 SelfCare 把该 ID 的热点密码删掉重新手打一遍再保存")
                  .append("—— 官方明确警告复制粘贴会带进首尾空格。");

                fail(sb.toString());
                return;
            }

            /* MSTNAK 是「暂时性」的：BrandMeister 对同一个 Radio ID
             * 只允许一个会话在线，旧会话要过一会儿才被回收。
             * 这里不报错、不关 socket、**更不换端口**，等窗口过去自动重试。
             *
             * <p>早先版本在这里做了两件帮倒忙的事，已删掉：
             * ① 主动发 RPTCL「登出旧会话」—— 上游 MMDVMHost 只在
             *    `m_status == RUNNING`（真正登录成功后）才发 RPTCL，
             *    从没登录成功的套接字发出去毫无意义，只是噪声；
             * ② 换一个新套接字（新源端口）再试 —— 主站按「IP+端口」认热点，
             *    换端口等于把自己变成一台新热点，正好踩中
             *    「一个 ID 只允许一台热点在线」的判定，越重试越连不上。 */
            int waitMs = NAK_WAITS_MS[Math.min(nakCount - 1, NAK_WAITS_MS.length - 1)];
            nakWaitUntil = System.currentTimeMillis() + waitMs;
            handshakeDeadline = nakWaitUntil + HANDSHAKE_TIMEOUT_MS;
            lastNakStage = was.name();

            /* 之前那轮握手已经作废，清掉可重发的包，避免重发过期的 RPTK/RPTC */
            lastRptk = null;
            lastRptc = null;

            log("主站返回 MSTNAK（第 " + nakCount + "/" + (MAX_NAK - 1) + " 次，"
                    + "当时状态 " + was + "，登录 ID " + loginId
                    + "，本机端口 " + localPort + "）"
                    + "—— 等 " + (waitMs / 1000) + " 秒后用同一个端口重试");

            /* 状态行给「原因 + 下一步」而不是一句含糊的「暂时拒绝」：
             * 卡在 CONNECTING 时，唯一有效的解法是换 ESSID 别名 ID，
             * 所以直接把这句话写在用户眼前。 */
            String why;
            switch (was) {
                case CONNECTING:
                    why = essid > 0
                            ? "该别名 ID 也已有会话在线"
                            : "这个 ID 已有热点在线，去填 ESSID 别名 ID 就能并存";
                    break;
                case AUTH:
                    why = "热点安全密码没通过";
                    break;
                case CONF:
                    why = "身份/配置被拒";
                    break;
                default:
                    why = "被同 ID 的另一台设备顶掉";
                    break;
            }
            setState(State.CONNECTING, "主站拒绝登录 ID " + loginId + "（" + why + "），第 "
                    + nakCount + "/" + (MAX_NAK - 1) + " 次，"
                    + (waitMs / 1000) + " 秒后自动重试…");

            /* ⚠ 关键：握手线程在进入 CONNECTED 时就退出了。
             * 如果是「连着的时候被踢」，此刻没有任何线程会去重试 ——
             * 界面会永远停在上面那行，表现就是「卡着没变化」。 */
            ensureHandshakeThread();
            return;
        }

        if (startsWith(buf, len, "MSTCL", 5)) {
            /* 主站主动关闭：最常见是被别的设备用同一个 Radio ID 顶掉，
             * 或者主站重启。上游 DroidStar 在这里是 10 秒后重连，
             * 我们也自动重连 —— 免得用户又要自己点半天。 */
            logRxStats("主站关闭连接");
            if (autoReconnects >= MAX_AUTO_RECONNECT) {
                log("主站关闭了连接，已自动重连 " + autoReconnects + " 次，停止重连");
                setState(State.ERROR, "主站关闭了连接（已自动重连 "
                        + autoReconnects + " 次仍未成功）");
                shutdownAfterError();
                return;
            }
            autoReconnects++;
            log("主站关闭了连接 —— " + (AUTO_RECONNECT_WAIT_MS / 1000) + " 秒后自动重连（第 "
                    + autoReconnects + "/" + MAX_AUTO_RECONNECT + " 次）。"
                    + "常见原因：同一个 Radio ID 被别的设备顶掉了");
            setState(State.CONNECTING, "主站关闭了连接，"
                    + (AUTO_RECONNECT_WAIT_MS / 1000) + " 秒后自动重连…");
            /* 会话已被主站销毁，重连不用再等 hand-off 窗口 */
            hadSession = false;
            shutdownAfterError();
            scheduleReconnect();
            return;
        }

        // RPTACK 共出现三次，靠状态机区分
        if (state != State.CONNECTED && startsWith(buf, len, "RPTACK", 6)) {
            byte[] out = null;

            switch (state) {
                case CONNECTING: {
                    if (len < 10) {
                        log("RPTACK 长度不足，无法取 salt");
                        return;
                    }
                    byte[] salt = new byte[]{buf[6], buf[7], buf[8], buf[9]};

                    /* 把密码指纹打进日志。
                     *
                     * 每次 MSTNAK 落在 AUTH 时，第一个要回答的问题是
                     * 「密码到底有没有变」—— 但日志里不能写明文。
                     * 指纹（SHA-256 前 8 位十六进制）能回答这个问题：
                     * 两次登录的指纹一样 = 密码没变，别再让用户去改密码。 */
                    log("密码指纹 " + pwdFingerprint() + "（" + password.length() + " 字符）"
                            + (password.isEmpty()
                               ? "  ⚠ 空密码 —— 设置里的「热点安全密码」没填"
                               : (password.length() > 20
                                  ? "  ⚠ 超过 20 字符，BrandMeister 会拒绝"
                                  : "")));

                    byte[] pwd = password.getBytes(StandardCharsets.UTF_8);
                    byte[] in = new byte[4 + pwd.length];
                    System.arraycopy(salt, 0, in, 0, 4);
                    System.arraycopy(pwd, 0, in, 4, pwd.length);

                    byte[] digest = sha256(in);

                    out = new byte[40];
                    out[0] = 'R'; out[1] = 'P'; out[2] = 'T'; out[3] = 'K';
                    putBE32(out, 4, loginId);
                    System.arraycopy(digest, 0, out, 8, 32);

                    lastRptk = out;      // 留一份，停滞时原样重发
                    setState(State.AUTH, "已回 RPTK（salt+密码 的 SHA-256）");
                    break;
                }

                case AUTH: {
                    out = buildRptc();
                    lastRptc = out;      // 留一份，停滞时原样重发
                    setState(State.CONF, "已发 RPTC 配置");
                    break;
                }

                case CONF: {
                    // 第三个 RPTACK：登录完成
                    hadSession = true;
                    lastOkHost = host;
                    lastOkLoginId = loginId;
                    /* 记下「这份凭据在这个时刻通过了校验」——
                     * 之后再被拒时就能区分「密码变了」和「主站侧不认」 */
                    lastAuthOkAtMs = System.currentTimeMillis();
                    lastAuthOkFp = pwdFingerprint();
                    setState(State.CONNECTED, "已连接，可以发射");
                    /* 从这一刻开始计时，pingLoop 用它判断链路是不是还活着。
                     * 注意 lastPongMs 只在「真收到过 MSTPONG」之后才有意义 ——
                     * 有些主站根本不回 pong，那种情况一律不做超时判断（见 pingLoop）。 */
                    lastPongMs = 0;
                    log("登录完成，保活间隔 " + (PING_INTERVAL_MS / 1000) + " 秒");
                    break;
                }

                default:
                    return;
            }

            if (out != null && out.length > 0) send(out);
            return;
        }

        if (len == 11 && startsWith(buf, len, "MSTPONG", 7)) {
            boolean first = (lastPongMs == 0);
            pongCount++;
            lastPongMs = System.currentTimeMillis();
            pingsSincePong = 0;   // 心跳判定用的是「连发几个没回」，收到就清零
            /* 第一次收到 pong 很关键：它决定 pingLoop 里的「心跳超时」判断要不要启用。
             * 有些主站根本不回 MSTPONG，对它们做超时判断会变成每 25 秒自杀一次。 */
            if (first) log("收到主站 MSTPONG，心跳正常 —— 之后连续 "
                    + (PONG_MISS_LIMIT * PING_INTERVAL_MS / 1000) + " 秒收不到才判定链路断开");
            if (listener != null) listener.onPong(pongCount);
            return;
        }

        if (len == 55 && startsWith(buf, len, "DMRD", 4)) {
            /* 先记序号：DMRD[4] 是 8 位循环序号，主站每 60 ms 加一。
             * 差 1 = 正常；差 0 = 重复包；差 2~63 = 中间丢了 (差-1) 个；
             * 差得离谱（>=64）多半是换话组或重连，重置基线不累加。 */
            int seq = buf[4] & 0xFF;
            if (dmrdSeqLast >= 0) {
                int gap = (seq - dmrdSeqLast) & 0xFF;
                if (gap == 0) {
                    /* 重复包，不算丢 */
                } else if (gap < 64) {
                    dmrdLostCount += (gap - 1);
                } else {
                    dmrdReorderCount++;
                }
            }
            dmrdSeqLast = seq;
            dmrdRxCount++;
            /* ★ 收到任何 DMRD 都说明链路是活的，把「漏 pong」的账一起清了。
             *
             * 原来只有 MSTPONG 才清零。挂机守听时主站偶尔连续丢几个 pong
             * （或者干脆少回几个），但只要还有人说话、DMRD 一直在到，
             * 链路就百分百是通的 —— 这时候判「心跳超时」去重连纯属自找麻烦。
             * 收到别人的语音包是比 pong 更硬的存活证据。 */
            pingsSincePong = 0;
            lastPongMs = System.currentTimeMillis();

            int srcId = ((buf[5] & 0xFF) << 16) | ((buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);
            int dstId = ((buf[8] & 0xFF) << 16) | ((buf[9] & 0xFF) << 8) | (buf[10] & 0xFF);
            int flags = buf[15] & 0xFF;
            int slot = (flags & DmrRx.FLAG_SLOT2) != 0 ? 2 : 1;
            /* bit5 置位表示数据/同步帧；语音帧时该位为 0 */
            boolean voice = (flags & DmrRx.FLAG_DATA) == 0;

            /* 语音帧才拷载荷 —— 数据帧（LC 头 / 终止帧）没有音频，
             * 白拷一份 33 字节只是给 GC 找活干。 */
            byte[] payload = null;
            if (voice) {
                payload = new byte[DmrRx.PAYLOAD_BYTES];
                System.arraycopy(buf, DmrRx.PAYLOAD_OFF, payload, 0, DmrRx.PAYLOAD_BYTES);
            }

            if (listener != null) listener.onDmrd(srcId, dstId, slot, voice, payload, flags);
            return;
        }

        log("收到未知报文 " + len + " 字节：" + hex(buf, Math.min(len, 16)));
    }

    private void shutdownAfterError() {
        running = false;
        DatagramSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 排一个延时自动重连。
     *
     * <p>醒来时先比对会话代数：用户若在这期间按了「断开」，代数会变，
     * 那就不能自己连回去（否则用户断了它又连上，很莫名其妙）。
     */
    private void scheduleReconnect() {
        final int gen = sessionGen;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(AUTO_RECONNECT_WAIT_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (gen != sessionGen) {
                log("用户已主动断开，取消自动重连");
                return;
            }
            if (socket != null) {
                log("已经有连接了，取消自动重连");
                return;
            }
            log("自动重连…");
            connectInternal(true);
        }, "mmdvm-reconnect");
        t.setDaemon(true);
        t.start();
    }

    /* ================================================================ */
    /* 保活                                                             */
    /* ================================================================ */

    private void pingLoop() {
        while (running) {
            try {
                Thread.sleep(PING_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!running) return;

            /* 兜底看护：接收线程万一还是死了，这里把它拉起来。
             * 没有这一步，界面会一直显示「已连接」但收不到任何东西
             * （没有语音、没有 MSTPONG），只能杀进程重启。 */
            ensureRxThread();

            if (state != State.CONNECTED) {
                /* 没连上时这个线程顺便当看护。
                 *
                 * 握手线程一旦退出就没人负责重试，而它退出有两种时机：
                 * 一是进入 CONNECTED，二是刚好在「收到 MSTNAK 被推回 CONNECTING」
                 * 的那一瞬间正在退出（isAlive() 还是 true，于是 ensure 没补上）。
                 * 后者窗口很小但真实存在 —— 这里每 5 秒兜一次，
                 * 保证界面绝不会永远停在「N 秒后自动重试」。 */
                if (state != State.ERROR) ensureHandshakeThread();
                continue;
            }

            /* 链路存活判断 —— 必须放在**发下一个 ping 之前**。
             *
             * ★ v2.4.8 把这段从「发完再判」挪到「发之前判」。
             * 原来的顺序是 send → 计数 +1 → 立刻判，于是第 12 个 ping 刚发出去
             * 就被算成「没回」，它的 pong 根本还没机会到 —— 等于凭空少容忍一个周期，
             * 而且最后一个 ping 永远白发。
             *
             * 挪到前面之后，每个 ping 都有完整的一个间隔去等它的 pong。
             *
             * 之所以把「已建立的会话收到的 MSTNAK」一律忽略（见 handle），是因为那种
             * NAK 大多是延迟到达的旧报文；代价是万一主站真的把我们悄悄踢了，
             * 界面会一直显示「已连接」。这里用 MSTPONG 兜住：
             * 连续发 PONG_MISS_LIMIT 个 ping 都没换回 pong 就认定链路已死，主动重连。
             *
             * ⚠ 两个前提，缺一个就会误杀：
             *   ① 本次会话收到过至少一个 pong —— 有些主站根本不回 MSTPONG，
             *      对它们做超时判断会变成每分钟自杀一次；
             *   ② 用「发了几个 ping」而不是「墙上时钟过了多久」——
             *      熄屏后进程被冻结，时钟照走而 ping 没发，用时间判断必然误判。 */
            if (lastPongMs > 0 && pingsSincePong >= PONG_MISS_LIMIT) {
                long silence = System.currentTimeMillis() - lastPongMs;
                log("连发 " + pingsSincePong + " 个 RPTPING 都没收到 MSTPONG（已 "
                        + (silence / 1000) + " 秒），判定链路已断，重连");
                lastPongMs = 0;
                pingsSincePong = 0;
                hadSession = false;
                setState(State.CONNECTING, "主站心跳超时，正在重连…");
                shutdownAfterError();
                scheduleReconnect();
                return;
            }

            byte[] ping = new byte[11];
            ping[0] = 'R'; ping[1] = 'P'; ping[2] = 'T';
            ping[3] = 'P'; ping[4] = 'I'; ping[5] = 'N'; ping[6] = 'G';
            putBE32(ping, 7, loginId);
            send(ping);
            pingsSincePong++;
        }
    }

    /**
     * 保证接收线程活着。
     *
     * <p>接收线程是整条链路的命门：它一死，既收不到语音也收不到 MSTPONG，
     * 界面却仍然显示「已连接」—— 用户看到的就是「有人正在通联但没声音，
     * 只能重启软件」。这里每 5 秒兜一次，死了就原地拉起来。
     */
    private void ensureRxThread() {
        Thread t = rxThread;
        if (t != null && t.isAlive()) return;
        if (!running) return;
        log("接收线程已停止，重新拉起（不影响当前登录状态）");
        t = new Thread(this::rxLoop, "mmdvm-rx");
        t.setDaemon(true);
        rxThread = t;
        t.start();
    }

    /* ================================================================ */
    /* 发送                                                             */
    /* ================================================================ */

    /**
     * 发送一个已经组装好的 DMRD 包。
     *
     * @return 是否发送成功
     */
    public boolean sendDmrd(byte[] packet, int len) {
        if (state != State.CONNECTED) return false;
        return send(packet, len);
    }

    private boolean send(byte[] data) {
        return send(data, data.length);
    }

    private boolean send(byte[] data, int len) {
        DatagramSocket s = socket;
        if (s == null || master == null) return false;

        try {
            DatagramPacket pkt = new DatagramPacket(data, len, master, port);
            synchronized (txLock) {
                s.send(pkt);
            }
            txPackets++;
            /* 握手阶段的发送也打出来；业务帧（55 字节 DMRD）太密，不打 */
            if (state != State.CONNECTED && len != 55) {
                log("→ " + len + " 字节 [" + asciiOf(data, len) + "] "
                        + hex(data, Math.min(len, 24)) + (len > 24 ? " …" : ""));
            }
            return true;
        } catch (IOException e) {
            if (running) log("发送失败：" + e.getMessage());
            return false;
        }
    }

    /* ================================================================ */
    /* RPTC 配置串                                                      */
    /* ================================================================ */

    /**
     * 构造 302 字节的 RPTC 配置包。
     *
     * <p>格式串与 DroidStar 完全一致，各字段宽度之和为 294：
     * <pre>
     *  %-8.8s  %09u  %09u  %02u  %02u  %8.8s  %9.9s  %03d
     *  %-20.20s  %-19.19s  %c  %-124.124s  %-40.40s  %-40.40s
     * </pre>
     * 两个 %09u 分别是收/发频率，这里填同一个值；%c 固定为 '4'。
     */
    private byte[] buildRptc() {
        byte[] b = new byte[302];
        b[0] = 'R'; b[1] = 'P'; b[2] = 'T'; b[3] = 'C';
        /* 配置包也带 loginId —— 主站拿它去找设备记录，
         * 带 ESSID 时匹配的是别名 ID 那条记录，不会去动原 ID 那条。 */
        putBE32(b, 4, loginId);

        /* 呼号仍然报裸 DMR ID 对应的呼号（身份是人的，不是热点的） */
        String cs = callsign.isEmpty() ? String.valueOf(dmrId) : callsign;

        /* MMDVM 配置串是「单字节字符集」的定宽串：位置 20 / 描述 19 / url 124，
         * 宽度按字符数算。中文这类非 ASCII 字符经 ISO-8859-1 编码会变成 '?'，
         * 一个中文字符 → 一个 '?'，不会撑破 294 字节预算，但主站侧看到的是问号。
         * 这里先显式清洗，再在日志里说清楚 —— 免得用户以为填的位置/描述没生效。 */
        boolean asciiLoss = hasNonAscii(location) || hasNonAscii(description) || hasNonAscii(url);
        String loc = toAscii(location, 20);
        String desc = toAscii(description, 19);
        String u = toAscii(url, 124);

        /* 这两个字段是 BrandMeister 在 CONF 阶段真正会校验的：
         * softwareId 必须是合法 8 位日期、packageId 必须以 MMDVM 开头。
         * 发送前再过一道，避免任何路径把非法值塞进来。 */
        String swid = sanitizeSoftwareId(softwareId);
        String pkid = sanitizePackageId(packageId);

        /* ★ 经度这里踩过一个坑，别再改回去。
         *
         * 纬度 %8.8s / 经度 %9.9s 都是「宽度 9、内容右对齐补空格」。
         * 上游 DroidStar 的写法是 sprintf("%08f") / ("%09f") 先转成字符串，
         * 0.0 → "0.000000"（8 位）和 "00.000000"（9 位，零填充）；
         * 经 %8.8s / %9.9s 之后分别是 "0.000000" 和 "00.000000"。
         *
         * 我们早先在这里写的是 "0.0000000"（9 字符，但 7 位小数）——
         * 长度凑够了 9，看上去没问题，实测 BrandMeister 一律回 MSTNAK：
         *   经度 " 0.000000" → CONNECTED
         *   经度 "0.0000000" → MSTNAK
         * 主站是按「整数位.6 位小数」的定长格式解析的，多一位小数就解析不出来。
         * 所以这里必须给 "0.000000"，让 %9.9s 自己右对齐补前导空格。 */
        String cfg = String.format(Locale.US,
                "%-8.8s%09d%09d%02d%02d%8.8s%9.9s%03d%-20.20s%-19.19s%c%-124.124s%-40.40s%-40.40s",
                cs, freqHz, freqHz, 1, 1,
                "0.000000", "0.000000", 0,
                loc, desc, '4',
                u, swid, pkid);

        byte[] sb = cfg.getBytes(StandardCharsets.ISO_8859_1);
        int n = Math.min(sb.length, 294);
        System.arraycopy(sb, 0, b, 8, n);

        /* 把**每一个**字段都写进日志。
         * 卡在 CONF 被拒时，主站是拿「呼号」跟 DMR ID 的注册信息比对的，
         * 但配置串里还有十几个字段。只写字节数根本看不出问题在哪 ——
         * 全字段打出来，才能和「现在能连上的那台客户端」逐项对照。 */
        log("RPTC 配置串 " + n + " 字节（应为 294）｜呼号 " + cs.trim()
                + " · 登录 ID " + loginId
                + (essid > 0 ? "（别名 " + String.format(Locale.US, "%02d", essid) + "）" : "（未用别名）"));
        log("  · 收发频率 " + String.format(Locale.US, "%.6f", freqHz / 1_000_000.0)
                + " MHz（收=发）· 时隙 1/1 · 色码不在本串内");
        log("  · 纬度 0.000000 · 经度 0.000000 · 类型 000 · 类型字符 '4'");
        log("  · 位置 \"" + loc + "\" · 描述 \"" + desc + "\" · url \"" + u + "\"");
        log("  · 软件 " + swid + " · 硬件 " + pkid);
        if (asciiLoss) {
            log("⚠ 位置/描述/url 里有非 ASCII 字符（中文等），MMDVM 配置串只支持单字节字符集，"
                    + "已替换成 '?' —— 主站侧显示会是问号，但不影响登录判定");
        }
        if (callsign.isEmpty()) {
            log("⚠ 呼号为空，本次用 DMR ID 当呼号发上去了 —— 主站会拿它跟注册信息比对，"
                    + "几乎必然在配置阶段回 MSTNAK。请在「主站与身份」里填上与 DMR ID 一致的呼号");
        }
        return b;
    }

    /** 有没有超出可打印 ASCII 的字符（中文、全角标点、控制符等） */
    private static boolean hasNonAscii(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) return true;
        }
        return false;
    }

    /** 非 ASCII 换成 '?'，并截到 maxChars 个字符 —— 保证字节数完全可预期 */
    private static String toAscii(String s, int maxChars) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(Math.min(s.length(), maxChars));
        for (int i = 0; i < s.length() && sb.length() < maxChars; i++) {
            char c = s.charAt(i);
            sb.append(c >= 0x20 && c <= 0x7E ? c : '?');
        }
        return sb.toString();
    }

    /* ================================================================ */
    /* 工具                                                             */
    /* ================================================================ */

    private static void putBE32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    /** 报文开头的可打印 ASCII，用来一眼看出是 RPTACK / MSTNAK / DMRD */
    private static String asciiOf(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && i < 12; i++) {
            int c = b[i] & 0xFF;
            sb.append(c >= 32 && c < 127 ? (char) c : '.');
        }
        return sb.toString();
    }

    private static boolean startsWith(byte[] b, int len, String tag, int n) {
        if (len < n) return false;
        for (int i = 0; i < n; i++) {
            if (b[i] != (byte) tag.charAt(i)) return false;
        }
        return true;
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String hex(byte[] b, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append(String.format(Locale.US, "%02X ", b[i]));
        return s.toString().trim();
    }

    private void setState(State s, String detail) {
        state = s;
        log("[状态] " + s + " —— " + detail);
        if (listener != null) listener.onState(s, detail);
    }

    /**
     * 只更新状态与界面，<b>不写日志</b>。
     *
     * <p>给「重试倒计时」这类每秒刷新用的 —— 走 {@link #setState} 的话，
     * 光是等一个 20 秒窗口就会刷出 20 行日志，把真正有用的握手报文淹掉。
     */
    private void setStateQuiet(State s, String detail) {
        state = s;
        if (listener != null) listener.onState(s, detail);
    }

    private void fail(String msg) {
        setState(State.ERROR, msg);
    }

    private void log(String line) {
        if (listener != null) listener.onLog(line);
    }
}
