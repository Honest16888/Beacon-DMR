package net.beacon.dmr.live;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import net.beacon.dmr.hose.WsClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BrandMeister 实时活动流（Last Heard）客户端。
 *
 * <p>地址 {@code wss://api.brandmeister.network/lh/?version=2&EIO=4&transport=websocket}，
 * 上面套了两层协议，逐帧扒出来的：
 *
 * <pre>
 *   ← 0{"sid":...,"pingInterval":25000,...}   Engine.IO 握手
 *   → 40                                       Socket.IO 连默认命名空间
 *   ← 40{"sid":...}                            连上了
 *   → 42["join","everything"]                  订阅全球
 *   → 42["sendCurrentSessions"]                要一份当前会话快照
 *   ← 2  /  → 3                                Engine.IO 心跳（25 秒一次）
 *   ← 42["mqtt",{"topic":"LH","payload":"{...}"}]   一条通联报文
 * </pre>
 *
 * <p><b>为什么不用 MQTT 库</b>：官方这个接口虽然字段叫 mqtt，实际是
 * Socket.IO 把 MQTT 报文当字符串转发，根本不需要 MQTT 协议栈。
 * 按 JS 那套逐帧处理就够了，零依赖。
 *
 * <p>实测：连上后 12 秒收到 108 条报文（约 9 条/秒），所以读线程里
 * 只做解析 + 丢给 {@link LiveStore}，绝不做界面操作。
 *
 * <p>回调：{@link Listener#onState} 在主线程，{@link Listener#onSession}
 * 在 WebSocket 读线程 —— 后者每秒来十几条，切主线程纯属浪费，
 * 由 {@link LiveStore} 自己保证线程安全。
 */
public final class LhClient {

    private static final String TAG = "LhClient";

    public static final String URL =
            "wss://api.brandmeister.network/lh/?version=2&EIO=4&transport=websocket";

    public enum State { IDLE, CONNECTING, OPEN, RETRY }

    public interface Listener {
        void onState(State s, String detail);

        /** 一条已解析的 Last Heard 报文。在读线程上调用。 */
        void onEvent(LiveStore.Event e);
    }

    private final Listener cb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);

    private WsClient ws;
    private int retry = 0;
    private Runnable pendingRetry;

    public LhClient(Listener l) { this.cb = l; }

    /* ==================== 生命周期 ==================== */

    public void start() {
        if (running.getAndSet(true)) return;
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

    public boolean isOpen() {
        WsClient c = ws;
        return c != null && c.isOpen();
    }

    private void connect() {
        if (!running.get()) return;
        post(() -> cb.onState(State.CONNECTING, retry > 0 ? "第 " + retry + " 次重试" : "连接中"));
        WsClient c = new WsClient(URL, null, new WsClient.Listener() {
            @Override public void onOpen(WsClient self) { /* 等 Engine.IO 的 0 帧再动手 */ }

            @Override public void onBinary(byte[] data) { /* LH 全是文本帧 */ }

            @Override public void onText(String text) { onFrame(text); }

            @Override public void onClosed(String reason) { scheduleRetry(reason); }
        });
        ws = c;
        new Thread(() -> c.connect(), "lh-connect").start();
    }

    private void scheduleRetry(String reason) {
        if (!running.get()) return;
        cancelRetry();
        retry++;
        /* 快重试 20 次（1.2s 起，封顶 15s），之后转 45 秒慢轮询，永不放弃。
         * 看板挂机一整天，网络抖一下不该就此永久停更。 */
        boolean fast = retry <= 20;
        int delay = fast ? Math.min(1200 * retry, 15000) : 45000;
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

    /* ==================== 帧 ==================== */

    private void onFrame(String raw) {
        if (raw == null || raw.isEmpty()) return;
        char pt = raw.charAt(0);
        try {
            switch (pt) {
                case '0':
                    send("40");
                    return;
                case '2':
                    send("3");
                    return;
                case '1':
                    closeSelf();
                    return;
                case '4':
                    if (raw.length() > 1 && raw.charAt(1) == '0') {
                        retry = 0;
                        post(() -> cb.onState(State.OPEN, "已连接"));
                        send("42[\"join\",\"everything\"]");
                        send("42[\"sendCurrentSessions\"]");
                        return;
                    }
                    if (raw.length() > 1 && raw.charAt(1) == '2') {
                        onEvent(raw);
                        return;
                    }
                    return;
                default:
                    /* 3（pong）、6（noop）之类，忽略。 */
                    return;
            }
        } catch (Exception e) {
            /* 单帧解析失败只丢这一帧，不能把读线程带走。 */
            Log.w(TAG, "帧处理异常：" + e);
        }
    }

    private void onEvent(String raw) {
        /* 形如 42["mqtt",{...}]；Socket.IO 还可能在 "42" 后面塞一个 ack id，
         * 这里先跳过数字再找 '['。 */
        int i = 2;
        while (i < raw.length() && Character.isDigit(raw.charAt(i))) i++;
        if (i >= raw.length() || raw.charAt(i) != '[') return;
        JSONArray arr;
        try {
            arr = new JSONArray(raw.substring(i));
        } catch (Exception e) {
            return;
        }
        if (arr.length() < 2) return;
        if (!"mqtt".equals(arr.optString(0))) return;
        JSONObject env = arr.optJSONObject(1);
        if (env == null) return;
        String payload = env.optString("payload", "");
        if (payload.isEmpty()) return;
        JSONObject p;
        try {
            p = new JSONObject(payload);
        } catch (Exception e) {
            return;
        }
        cb.onEvent(parse(p));
    }

    /** 官方 JSON → {@link LiveStore.Event}。字段名一个都不能错，错了就是整项统计为 0。 */
    private static LiveStore.Event parse(JSONObject p) {
        LiveStore.Event e = new LiveStore.Event();
        String sid = p.optString("SessionID", "");
        long ctx = p.optLong("ContextID", 0);
        e.key = ctx != 0 ? ("c" + ctx) : ("s" + sid);
        e.tg = p.optLong("DestinationID", 0);
        e.srcId = p.optLong("SourceID", 0);
        e.call = p.optString("SourceCall", "");
        e.name = p.optString("SourceName", "");
        e.alias = p.optString("TalkerAlias", "");
        e.master = p.optString("Master", "");
        e.slot = p.optInt("Slot", 0);
        e.start = p.optLong("Start", 0);
        e.stop = p.optLong("Stop", 0);
        e.event = p.optString("Event", "");
        JSONArray ct = p.optJSONArray("CallTypes");
        if (ct != null) {
            for (int k = 0; k < ct.length(); k++) {
                if ("Private".equals(ct.optString(k))) { e.priv = true; break; }
            }
        }
        return e;
    }

    private void send(String s) {
        WsClient c = ws;
        if (c == null || !c.isOpen()) return;
        try {
            c.sendText(s);
        } catch (Exception ignore) {
            /* 发不出去说明连接已经废了，交给 onClosed 的重连。 */
        }
    }

    private void closeSelf() {
        WsClient c = ws;
        if (c != null) c.close();
    }
}
