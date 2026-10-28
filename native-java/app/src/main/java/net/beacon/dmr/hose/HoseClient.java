package net.beacon.dmr.hose;

import android.os.Handler;
import android.os.Looper;

import net.beacon.dmr.core.MsgPack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BrandMeister Hoseline（HOSE）收听客户端。
 *
 * <p>协议（从官方网页播放器的流量里扒出来的，和 WebView 版的实现完全一致）：
 * <pre>
 *   wss://hose.brandmeister.network/spotter/?token=test:test   子协议 "spotter"
 *   报文是 MessagePack 数组，第 0 项是类型：
 *     1  GROUP_JOIN     发：[1, [话组...]]      收：无
 *     2  GROUP_LEAVE    发：[2, [话组...]]
 *     11 CALL_START     收：[11, 0, srcId, tg]
 *     12 CALL_DROP      收：[12, ...]
 *     13 CALL_END       收：[13, 0, srcId, tg]
 *     20 CALL_AUDIO     收：[20, bin(160 字节 μ-law = 20 ms)]
 *     21 CALL_ALIAS     收：[21, "呼号 名字 位置"]
 *     22 CALL_METER     收：[22, ...]
 * </pre>
 *
 * <p>注意音频帧**不带话组号** —— 归属只能靠最近一个 CALL_START 记下来。
 * WebView 版在这上面栽过（RXG.attrTg），这里用 {@link #attrTg} 同样的思路。
 *
 * <p>回调全部在主线程上抛，调用方可以直接动 View。
 */
public final class HoseClient {

    public static final String URL_BM = "wss://hose.brandmeister.network/spotter/?token=test:test";

    /* 协议常量 —— 数值必须和官方一致，写错一个就是一个功能静默失效。 */
    public static final int T_GROUP_JOIN = 1;
    public static final int T_GROUP_LEAVE = 2;
    public static final int T_GROUP_RESET = 3;
    public static final int T_CALL_START = 11;
    public static final int T_CALL_DROP = 12;
    public static final int T_CALL_END = 13;
    public static final int T_CALL_AUDIO = 20;
    public static final int T_CALL_ALIAS = 21;
    public static final int T_CALL_METER = 22;

    public enum State { IDLE, CONNECTING, OPEN, RETRY }

    public interface Listener {
        void onState(State s, String detail);
        void onCallStart(int srcId, int tg);
        void onCallEnd(int tg);
        void onAlias(int tg, String alias);
        /**
         * 一帧 20 ms 的 μ-law 音频。
         *
         * <p>★ 带上 {@code tg}：HOSE 的 CALL_AUDIO 帧**本身不带话组号**，
         * 归属只能靠最近一个 CALL_START 记下的 {@link #getAttrTg()}。
         * 不带这个参数的话，主界面收到就播 —— 订阅了 91 和 460 两个组时，
         * 两边的语音会一起出来，听感就是「乱播放别的组」。
         *
         * <p>{@code tg == 0} 表示归属不明（半路加入、CALL_START 丢了）。
         * 这种时候调用方**应该照播**，否则会变成「偶尔整段没声音」，
         * 比串音更难查 —— WebView 版的原话。
         */
        void onAudio(int tg, byte[] ulaw);
        void onMeter(int tg, int db);
    }

    private final Listener cb;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private WsClient ws;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int[] subs = new int[0];
    private int retry = 0;
    private Runnable pendingRetry;

    /** 最近一个 CALL_START 的话组。音频帧不带话组号，只能靠它归属。 */
    private volatile int attrTg = 0;

    public HoseClient(Listener l) { this.cb = l; }

    public int getAttrTg() { return attrTg; }

    /* ==================== 生命周期 ==================== */

    public void start(int[] talkgroups) {
        subs = talkgroups == null ? new int[0] : talkgroups.clone();
        if (running.getAndSet(true)) {
            /* 已经在跑：只更新订阅，不用重连。 */
            sendSubs();
            return;
        }
        retry = 0;
        connect();
    }

    public void stop() {
        running.set(false);
        cancelRetry();
        WsClient c = ws;
        ws = null;
        if (c != null) c.close();
        post(() -> cb.onState(State.IDLE, "已停止"));
    }

    /** 订阅变了。没连上时只记下来，连上那一刻会自动发。 */
    public void setSubscriptions(int[] talkgroups) {
        subs = talkgroups == null ? new int[0] : talkgroups.clone();
        if (running.get() && ws != null && ws.isOpen()) sendSubs();
    }

    private void sendSubs() {
        WsClient c = ws;
        if (c == null || !c.isOpen()) return;
        try {
            if (subs.length == 0) {
                /* 一个都不订阅时发 GROUP_RESET：不然上一轮的订阅会一直往这儿推音频。 */
                c.send(MsgPack.encodeIntArray(T_GROUP_RESET, new int[0]));
            } else {
                c.send(MsgPack.encodeIntArray(T_GROUP_JOIN, subs));
            }
        } catch (Exception e) {
            /* 发送失败等于连接已经废了，交给 onClosed 的重连逻辑。 */
        }
    }

    private void connect() {
        if (!running.get()) return;
        post(() -> cb.onState(State.CONNECTING, retry > 0 ? "第 " + retry + " 次重试" : "连接中"));
        WsClient c = new WsClient(URL_BM, "spotter", new WsClient.Listener() {
            @Override public void onOpen(WsClient self) {
                retry = 0;
                attrTg = 0;
                post(() -> cb.onState(State.OPEN, "已连接"));
                sendSubs();
            }
            @Override public void onBinary(byte[] data) { onFrame(data); }
            @Override public void onText(String text) { /* HOSE 只发二进制，文本帧忽略 */ }
            @Override public void onClosed(String reason) { scheduleRetry(reason); }
        });
        ws = c;
        new Thread(() -> {
            if (!c.connect() && running.get()) {
                /* connect() 失败时 WsClient 内部已经调过 onClosed，这里不用再排重试。 */
            }
        }, "hose-connect").start();
    }

    private void scheduleRetry(String reason) {
        if (!running.get()) return;
        cancelRetry();
        /* 快重试 12 次（1.5s 起，逐步拉到 12s），之后转 30s 慢轮询，**永不放弃**。
         * 挂机守听时网络断几分钟不该就此永久静默 —— WebView 版在这里踩过坑。 */
        retry++;
        int delay = retry <= 12 ? Math.min(1500 * retry, 12000) : 30000;
        post(() -> cb.onState(State.RETRY, reason + "，" + (delay / 1000) + " 秒后重连"));
        pendingRetry = () -> { ws = null; connect(); };
        ui.postDelayed(pendingRetry, delay);
    }

    private void cancelRetry() {
        if (pendingRetry != null) { ui.removeCallbacks(pendingRetry); pendingRetry = null; }
    }

    private void post(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else ui.post(r);
    }

    /* ==================== 报文 ==================== */

    private void onFrame(byte[] data) {
        List<Object> list;
        try {
            list = MsgPack.decodeAll(data);
        } catch (Exception e) {
            return;                      // 坏帧丢掉，别让一帧毁掉整条连接
        }
        for (Object o : list) {
            if (!(o instanceof List)) continue;
            @SuppressWarnings("unchecked")
            List<Object> m = (List<Object>) o;
            if (m.isEmpty()) continue;
            int type = asInt(m.get(0), -1);
            switch (type) {
                case T_CALL_START: {
                    int src = asInt(at(m, 2), 0);
                    int tg = asInt(at(m, 3), 0);
                    attrTg = tg;
                    post(() -> cb.onCallStart(src, tg));
                    break;
                }
                case T_CALL_END: {
                    int tg = asInt(at(m, 3), 0);
                    if (attrTg == tg) attrTg = 0;
                    post(() -> cb.onCallEnd(tg));
                    break;
                }
                case T_CALL_AUDIO: {
                    Object b = at(m, 1);
                    if (!(b instanceof byte[])) break;
                    byte[] pcm = (byte[]) b;
                    if (pcm.length == 0) break;
                    /* 归属用 attrTg（CALL_START 时立起来、CALL_END 时清掉）。
                     * 0 表示不知道是谁的，交给调用方按「照播」处理。 */
                    final int tg = attrTg;
                    post(() -> cb.onAudio(tg, pcm));
                    break;
                }
                case T_CALL_ALIAS: {
                    Object a = at(m, 1);
                    if (!(a instanceof String)) break;
                    String s = ((String) a).trim();
                    int tg = attrTg;
                    post(() -> cb.onAlias(tg, s));
                    break;
                }
                case T_CALL_METER: {
                    /* 电平表。字段位置官方没公开，这里按「[22, tg, db]」尽力解析，
                     * 解不出来就当没有 —— 波形本来就有自己的 RMS，不依赖它。 */
                    int tg = asInt(at(m, 1), 0);
                    int db = asInt(at(m, 2), 0);
                    post(() -> cb.onMeter(tg, db));
                    break;
                }
                default:
                    break;
            }
        }
    }

    private static Object at(List<Object> l, int i) {
        return (i >= 0 && i < l.size()) ? l.get(i) : null;
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try { return Integer.parseInt(((String) o).trim()); } catch (NumberFormatException ignore) { }
        }
        return def;
    }

    /** 只给测试用：把一帧喂进解析器，验证类型分派正确。 */
    List<String> debugParse(byte[] frame) {
        List<String> out = new ArrayList<>();
        List<Object> list = MsgPack.decodeAll(frame);
        for (Object o : list) out.add(String.valueOf(o));
        return out;
    }
}
