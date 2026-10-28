package net.beacon.dmr.cn;

import android.os.Handler;
import android.os.Looper;

import net.beacon.dmr.hose.WsClient;

import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 国内 China DMR（radiowo）收听链路。
 *
 * <p>和国际 BrandMeister 的 HOSE 是**两套完全不同的协议**，虽然都是 WebSocket：
 *
 * <table border="1">
 *   <tr><th></th><th>国际 BM（HOSE）</th><th>国内（radiowo）</th></tr>
 *   <tr><td>地址</td><td>{@code wss://spotter...}</td><td>{@code wss://860.radiowo.com/ws/listen}</td></tr>
 *   <tr><td>子协议</td><td>{@code "spotter"}</td><td>无</td></tr>
 *   <tr><td>信令</td><td>MessagePack 数组，第 0 项是类型</td><td>JSON 对象，{@code type} 字段</td></tr>
 *   <tr><td>订阅</td><td>{@code [1,[ids]]} 增量</td><td>{@code {"action":"subscribe","talkgroups":[..]}} <b>全量</b></td></tr>
 *   <tr><td>音频帧</td><td>160 字节 μ-law（20 ms），<b>不带话组号</b></td>
 *       <td>4 字节 stream_id + 480 字节 μ-law（60 ms），<b>自带流号</b></td></tr>
 *   <tr><td>心跳</td><td>无（靠读超时判死）</td><td><b>每 25 s 重发订阅</b></td></tr>
 * </table>
 *
 * <p>★ <b>最大的好处：服务端已经把 AMBE 解码成 PCM 了</b>，所以国内收听
 * <b>完全不需要声码器</b>。而 μ-law 8 kHz 恰好和 HOSE 解码后一模一样，
 * 所以 {@code AudioOut} 一行都不用改，两条链路共用同一个播放队列。
 *
 * <p>★ <b>60 秒空闲超时是这个协议最大的坑。</b> 实测「连上后什么都不发」必在
 * +60.2 s 被 {@code close(1006)}，而每 20 s 发一次订阅就能一直保持。
 * 挂机守听时绝大多数时间没有通话 —— 不加心跳就是「连上 → 60 秒后断 → 重连」，
 * 而重连那一两秒正好有人开口，开头就丢了。
 * 订阅报文本身是幂等的（服务端按全量替换处理），所以直接拿它当心跳最省事。
 *
 * <p>★ <b>国内是多流并发</b>（一个 WebSocket 上同时来多个话组的音频），
 * 这是 HOSE 做不到的 —— 那边帧里不带话组号，只能靠「最近一个 CALL_START」推断。
 * 这里每一帧都带 {@code stream_id}，能精确查到它属于哪个话组。
 */
public final class RwClient {

    /** 直播收听地址。**不带 query、不带子协议、无鉴权** —— 连上就是匿名收听。 */
    private static final String URL = "wss://860.radiowo.com/ws/listen";

    /**
     * 心跳间隔。服务端 60 s 空闲超时，25 s 留 35 s 余量，
     * 网络抖一下也不至于掉。实测 20 s 也可。
     */
    private static final int KEEPALIVE_MS = 25000;

    /** 音频帧头：4 字节大端 stream_id。 */
    private static final int HDR = 4;
    /** 每帧 μ-law 样本数：480 = 60 ms @ 8 kHz。 */
    public static final int FRAME_SAMPLES = 480;

    public enum State { IDLE, CONNECTING, OPEN, RETRY, CLOSED }

    public interface Listener {
        void onState(State s, String detail);

        /**
         * 一路通话开始。
         *
         * @param streamId 流号（无符号 32 位，实测可能 &gt; 2^31，所以用 long）
         * @param slot     时隙，<b>0 基</b>（服务端给的是 1 基）
         */
        void onCallStart(long streamId, int srcId, int tg, int slot, boolean priv);

        /** 一路通话结束。只带 stream_id —— 服务端不重复给 tg。 */
        void onCallEnd(long streamId);

        /**
         * 一帧音频。
         *
         * @param ulaw 480 字节 μ-law
         */
        void onAudio(int tg, byte[] ulaw);
    }

    private final Listener cb;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile WsClient ws;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger retry = new AtomicInteger(0);

    /** 已经发给服务端的订阅。全量替换语义，用于「变了才重发」的比对。 */
    private volatile int[] subs = new int[0];

    /**
     * stream_id → 话组号。
     *
     * <p>用 {@link ConcurrentHashMap}：写在读线程（onBinary/onText），
     * 读也在读线程，但 {@code stop()} 会从别的线程清空。
     *
     * <p><b>没有 TTL、没有上限</b> —— 这是照抄 WebView 版的行为。
     * CALL_END 丢了的话这一条会一直留着，但它只占几十字节，
     * 而且留着反而能让「晚到的帧」仍对上话组（短暂断线后很有用）。
     */
    private final Map<Long, Integer> streams = new ConcurrentHashMap<>();

    private Runnable ka;
    private Runnable pendingRetry;

    public RwClient(Listener l) {
        this.cb = l;
    }

    /* ==================== 生命周期 ==================== */

    public boolean isOpen() {
        WsClient c = ws;
        return c != null && c.isOpen();
    }

    /** 有订阅才连。空订阅直接不连 —— 连上了也没东西可听。 */
    public void start(int[] talkgroups) {
        subs = talkgroups == null ? new int[0] : talkgroups.clone();
        if (subs.length == 0) {
            stop();
            return;
        }
        if (running.getAndSet(true)) {
            /* 已经在跑：只需把新订阅发下去。 */
            sendSubs(subs);
            return;
        }
        retry.set(0);
        connect();
    }

    /** 改订阅。全量替换，变了才发。 */
    public void setSubscriptions(int[] talkgroups) {
        int[] want = talkgroups == null ? new int[0] : talkgroups.clone();
        if (sameSubs(want, subs)) return;
        subs = want;

        if (want.length == 0) {
            /* 一个都不订阅 = 没有可听的东西。直接关掉，别占着一条连接。
             * 留着的话服务端会一直往这儿推音频（虽然我们也不播），白耗流量。 */
            stop();
            return;
        }
        if (!running.get()) {
            start(want);
            return;
        }
        sendSubs(want);
    }

    /**
     * 逐元素比对，不是只比长度。
     *
     * <p>顺序变了也要重发 —— 「我的收听」支持排序，顺序是用户的意图，
     * 只比长度会把「换了顺序」当成没变。
     */
    private static boolean sameSubs(int[] a, int[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    public void stop() {
        running.set(false);
        retry.set(99);                   // 让 onclose 里的重连直接落到慢速分支
        cancelKa();
        Runnable t = pendingRetry;
        pendingRetry = null;
        if (t != null) ui.removeCallbacks(t);

        WsClient c = ws;
        ws = null;
        if (c != null) {
            /* ★ 必须先摘监听再 close，否则自己的关闭动作会触发自己的重连逻辑。
             * 同一个坑在 HoseClient / LhClient 里都注释过。 */
            c.setListener(null);
            try { c.close(); } catch (Throwable ignored) { }
        }
        streams.clear();
        subs = new int[0];
        post(() -> cb.onState(State.IDLE, "已停止"));
    }

    private void connect() {
        if (!running.get()) return;
        post(() -> cb.onState(retry.get() > 0 ? State.RETRY : State.CONNECTING,
                retry.get() > 0 ? "第 " + retry.get() + " 次重试" : "连接中"));

        WsClient c = new WsClient(URL, null, new WsClient.Listener() {
            @Override public void onOpen(WsClient self) {
                retry.set(0);
                ws = self;
                post(() -> cb.onState(State.OPEN, "已连接 radiowo"));
                /* 连上立刻发一次订阅 —— 服务端不会主动问你要什么。 */
                sendSubs(subs);
                startKa();
            }

            @Override public void onBinary(byte[] data) {
                onAudioFrame(data);
            }

            @Override public void onText(String text) {
                onSignal(text);
            }

            @Override public void onClosed(String why) {
                cancelKa();
                ws = null;
                streams.clear();
                if (!running.get()) return;
                post(() -> cb.onState(State.CLOSED, "断开：" + why));
                scheduleRetry();
            }
        });
        ws = c;
        new Thread(() -> {
            try {
                c.connect();
            } catch (Throwable t) {
                if (running.get()) {
                    post(() -> cb.onState(State.CLOSED, "连接失败：" + t));
                    scheduleRetry();
                }
            }
        }, "rw-connect").start();
    }

    /**
     * 重连退避：前 12 次 1.5s × n（封顶 12s），之后固定 30s，<b>永不放弃</b>。
     *
     * <p>「永不放弃」很重要：挂机守听时网络断几分钟不该就此永久静默。
     */
    private void scheduleRetry() {
        if (!running.get()) return;
        int n = retry.get();
        boolean fast = n < 12;
        if (fast) retry.set(n + 1);
        long delay = fast ? Math.min(1500L * (n + 1), 12000L) : 30000L;
        Runnable t = () -> {
            pendingRetry = null;
            connect();
        };
        pendingRetry = t;
        ui.postDelayed(t, delay);
    }

    /* ==================== 心跳 ==================== */

    private void startKa() {
        cancelKa();
        Runnable r = new Runnable() {
            @Override public void run() {
                if (!running.get() || !isOpen()) return;
                /* 订阅报文当心跳。空订阅不发 —— 服务端不需要知道「我什么都不要」。 */
                int[] cur = subs;
                if (cur.length > 0) sendSubs(cur);
                ui.postDelayed(this, KEEPALIVE_MS);
            }
        };
        ka = r;
        ui.postDelayed(r, KEEPALIVE_MS);
    }

    private void cancelKa() {
        Runnable r = ka;
        ka = null;
        if (r != null) ui.removeCallbacks(r);
    }

    /* ==================== 收发 ==================== */

    private void sendSubs(int[] list) {
        WsClient c = ws;
        if (c == null || !c.isOpen()) return;
        StringBuilder sb = new StringBuilder(64);
        sb.append("{\"action\":\"subscribe\",\"talkgroups\":[");
        for (int i = 0; i < list.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(list[i]);
        }
        sb.append("]}");
        try {
            c.sendText(sb.toString());
        } catch (Exception e) {
            /* 发失败等于连接废了，交给 onClosed 的重连。 */
        }
    }

    /** 信令帧：JSON 对象，只有两种 type，其它一律忽略（服务端就这两种）。 */
    private void onSignal(String text) {
        if (text == null || text.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(text);
            String type = o.optString("type", "");
            if ("call_start".equals(type)) {
                /* stream_id 可能大于 2^31（实测 3481804867）——
                 * 用 int 会变负数，当 Map 的键没问题，但和「后来那条 call_end」
                 * 就对不上了（一个是负数一个也是负数，其实能对上）。
                 * 用 long + & 0xFFFFFFFFL 才是无符号语义，和 JSON 里读出来的同值。 */
                long sid = o.optLong("stream_id", 0) & 0xFFFFFFFFL;
                int src = o.optInt("src_id", 0);
                int tg = o.optInt("dst_id", 0);
                /* 服务端 timeslot 是 1 基，存成 0 基（和 HOSE 的 slot 一致）。 */
                int slot = Math.max(1, o.optInt("timeslot", 1)) - 1;
                boolean priv = "private".equals(o.optString("call_type", ""));
                if (sid != 0) streams.put(sid, tg);
                post(() -> cb.onCallStart(sid, src, tg, slot, priv));
            } else if ("call_end".equals(type)) {
                long sid = o.optLong("stream_id", 0) & 0xFFFFFFFFL;
                streams.remove(sid);
                post(() -> cb.onCallEnd(sid));
            }
            /* 其它 type：服务端没有 ack / error / unsubscribe，一律忽略。 */
        } catch (Exception e) {
            /* 坏帧丢掉，别让一条毁掉整条连接。 */
        }
    }

    /**
     * 二进制帧：{@code [0..3] 大端 uint32 stream_id ‖ [4..] 480 × μ-law}。
     *
     * <p>合计 484 字节。实测 902 帧 / 436568 字节 → 每帧正好 480 字节样本。
     */
    private void onAudioFrame(byte[] b) {
        if (b == null || b.length <= HDR) return;

        long sid = ((long) (b[0] & 0xFF) << 24)
                | ((long) (b[1] & 0xFF) << 16)
                | ((long) (b[2] & 0xFF) << 8)
                | (b[3] & 0xFF);

        Integer tg = streams.get(sid);
        if (tg == null || tg <= 0) {
            /* 流号不在表里（CALL_START 丢了、或者刚断线重连）。
             * 丢掉而不是瞎猜一个话组 —— 猜错就是串音。 */
            return;
        }

        int n = b.length - HDR;
        byte[] pcm = new byte[n];
        System.arraycopy(b, HDR, pcm, 0, n);
        final int ftg = tg;
        post(() -> cb.onAudio(ftg, pcm));
    }

    private void post(Runnable r) {
        ui.post(r);
    }

    /** 给诊断用的一句话。 */
    public String describe() {
        return String.format(Locale.US, "%s · 订阅 %d 组 · 在途流 %d",
                isOpen() ? "已连接" : (running.get() ? "连接中" : "未连接"),
                subs.length, streams.size());
    }
}
