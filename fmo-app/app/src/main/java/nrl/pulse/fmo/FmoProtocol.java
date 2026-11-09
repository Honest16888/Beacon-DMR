package nrl.pulse.fmo;

import android.util.Log;
import nrl.pulse.net.FmoMqtt;
import nrl.pulse.net.FmoApi;
import nrl.pulse.cert.CertManager;

/**
 * FMO 协议主类 —— 信标 / 广播 / APRS / QSO / 语音 / 电台列表。
 *
 * 从混淆类反编译确认：FMO 用 **APRS 协议**做信标和位置报告！
 *
 * APRS 帧类型（4 种，从 dex 字符串提取）：
 *   APFMO0,TCPIP*::  消息帧（文本消息）
 *   APFMO1,TCPIP*>:  状态帧（状态报告）
 *   APFMO2,TCPIP*>:  消息帧（个性化消息，如 fmoapp）
 *   APFMO4,TCPIP*:=  位置帧（经纬度 + 电台信息）
 *
 * MQTT topic：
 *   fmo/stations              电台列表
 *   fmo/beacon/{callsign}/tx  信标发射（APRS 帧）
 *   fmo/beacon/{callsign}/rx  信标接收
 *   fmo/broadcast/global/tx   广播发射（APRS 帧）
 *   fmo/broadcast/global/rx   广播接收
 *   fmo/aprs/{callsign}/tx    APRS 上行
 *   fmo/aprs/{callsign}/rx    APRS 下行
 *   fmo/voice/{id}/tx         语音上行（AMBE 编码）
 *   fmo/voice/{id}/rx         语音下行（AMBE 编码）
 *   fmo/qso/{id}/tx           QSO 上行
 *   fmo/qso/{id}/rx           QSO 下行
 *
 * 限制：信标报文最大 512 字节（APRS 官方限制）。
 */
public class FmoProtocol implements FmoMqtt.Listener {

    private static final String TAG = "FmoProtocol";

    private FmoMqtt mqtt;
    private FmoApi api;
    private CertManager certMgr;
    private String callsign;
    private BeaconConfig beaconConfig = new BeaconConfig();
    private BroadcastConfig broadcastConfig = new BroadcastConfig();
    private Listener listener;

    public interface Listener {
        void onConnected();
        void onDisconnected(String reason);
        void onVoiceRx(byte[] ambe);           // AMBE 编码的语音
        void onBeacon(String from, String payload);
        void onBroadcast(String from, String payload);
        void onAprs(String from, double lat, double lon, String comment);
        void onStationList(String json);
        void onError(String msg);
    }

    public FmoProtocol(String mqttBroker, String apiUrl, CertManager certMgr,
                       String callsign, Listener listener) {
        this.certMgr = certMgr;
        this.callsign = callsign;
        this.listener = listener;
        this.mqtt = new FmoMqtt(mqttBroker, this);
        this.api = new FmoApi(apiUrl);
    }

    /** 连接 MQTT + 订阅全部 FMO topic */
    public void connect() {
        String certPem = certMgr.getPemField("cert_user.json");
        String keyPem = certMgr.getPemField("cert_devicekey.json");
        String caPem = certMgr.getPemField("cert_int.json") + "\n"
                     + certMgr.getPemField("cert_root.json");

        if (certPem == null || keyPem == null) {
            if (listener != null) listener.onError("缺少证书文件，先完成设备绑定");
            return;
        }

        mqtt.connect(certPem, keyPem, caPem);
    }

    private void subscribeAll() {
        mqtt.subscribe("fmo/stations", 1);
        mqtt.subscribe("fmo/beacon/+/rx", 1);
        mqtt.subscribe("fmo/broadcast/+/rx", 1);
        mqtt.subscribe("fmo/aprs/+/rx", 0);
        mqtt.subscribe("fmo/voice/+/rx", 0);
        mqtt.subscribe("fmo/qso/+/rx", 1);
    }

    // ---- 信标（APRS 格式）----
    public void sendBeacon() {
        if (!beaconConfig.enabled) return;
        beaconConfig.callsign = callsign;
        String aprsMsg = beaconConfig.toAprsFull();
        mqtt.publishText(beaconConfig.toTopic(), aprsMsg, 1);
        Log.i(TAG, "beacon sent (" + aprsMsg.length() + " bytes): " + aprsMsg);
    }

    /** 发送 APRS 位置报告（APFMO4） */
    public void sendPosition(double lat, double lon, String comment) {
        String latStr = formatAprsCoord(lat, true);
        String lonStr = formatAprsCoord(lon, false);
        String msg = "= " + latStr + "/" + lonStr + ">" + (comment != null ? comment : "");
        mqtt.publishText("fmo/aprs/" + callsign + "/tx", msg, 0);
    }

    /** 发送 APRS 状态报告（APFMO1） */
    public void sendStatus(String status) {
        String msg = ">APFMO1,TCPIP*:" + status;
        mqtt.publishText("fmo/aprs/" + callsign + "/tx", msg, 0);
    }

    /** 发送 APRS 消息（APFMO2） */
    public void sendAprsMessage(String text) {
        String msg = ">APFMO2,TCPIP*>:" + text;
        mqtt.publishText("fmo/aprs/" + callsign + "/tx", msg, 0);
    }

    // ---- 广播（APRS 格式）----
    public void sendBroadcast(String text) {
        broadcastConfig.text = text;
        String aprsMsg = broadcastConfig.toAprsBroadcast();
        mqtt.publishText(broadcastConfig.toTopic(), aprsMsg, 1);
    }

    // ---- 语音（AMBE 编码）----
    public void sendVoice(byte[] ambe) {
        mqtt.publish("fmo/voice/" + callsign + "/tx", ambe, 0, false);
    }

    // ---- QSO ----
    public void sendQso(String qsoId, String text) {
        mqtt.publishText("fmo/qso/" + qsoId + "/tx", text, 1);
    }

    // ---- 电台列表（REST）----
    public void fetchStations() {
        new Thread(() -> {
            String json = api.getStations();
            if (json != null && listener != null) {
                listener.onStationList(json);
            }
        }).start();
    }

    // ---- APRS 坐标格式 ----
    private String formatAprsCoord(double deg, boolean isLat) {
        int sign = deg >= 0 ? 1 : -1;
        deg = Math.abs(deg);
        int d = (int) deg;
        double m = (deg - d) * 60;
        String hemi;
        if (isLat) hemi = sign >= 0 ? "N" : "S";
        else hemi = sign >= 0 ? "E" : "W";
        int digits = isLat ? 2 : 3;
        return String.format("%0" + digits + "d%05.2f%s", d, m, hemi);
    }

    /** 解析 APRS 坐标：DDMM.mmN → 度 */
    private double parseAprsCoord(String s, boolean isLat) {
        s = s.trim();
        if (s.isEmpty()) return 0;
        char hemi = s.charAt(s.length() - 1);
        String num = s.substring(0, s.length() - 1);
        try {
            double ddmm = Double.parseDouble(num);
            int d = (int)(ddmm / 100);
            double m = ddmm - d * 100;
            double deg = d + m / 60.0;
            if (hemi == 'S' || hemi == 'W') deg = -deg;
            return deg;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- FmoMqtt.Listener ----
    @Override public void onConnected() {
        subscribeAll();
        if (listener != null) listener.onConnected();
    }
    @Override public void onDisconnected(String reason) {
        if (listener != null) listener.onDisconnected(reason);
    }
    @Override public void onError(String msg) {
        if (listener != null) listener.onError(msg);
    }

    @Override public void onMessage(String topic, byte[] payload) {
        String t = topic;
        String body = new String(payload);
        if (t.equals("fmo/stations")) {
            if (listener != null) listener.onStationList(body);
        } else if (t.contains("/beacon/")) {
            if (listener != null) listener.onBeacon(t, body);
        } else if (t.contains("/broadcast/")) {
            if (listener != null) listener.onBroadcast(t, body);
        } else if (t.contains("/aprs/")) {
            // 解析 APRS 帧
            if (body.startsWith(">APFMO4") || body.startsWith("= ") || body.startsWith("!")) {
                // 位置帧
                try {
                    int eq = body.indexOf("= ");
                    int slash = body.indexOf("/", eq + 2);
                    if (slash > 0) {
                        String latStr = body.substring(eq + 2, slash);
                        int lonEnd = body.indexOf(">", slash);
                        String lonStr = body.substring(slash + 1, lonEnd > 0 ? lonEnd : body.length());
                        double lat = parseAprsCoord(latStr, true);
                        double lon = parseAprsCoord(lonStr, false);
                        String comment = lonEnd > 0 ? body.substring(lonEnd + 1) : "";
                        if (listener != null) listener.onAprs(t, lat, lon, comment);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "APRS parse error: " + e.getMessage());
                }
            }
        } else if (t.contains("/voice/")) {
            if (listener != null) listener.onVoiceRx(payload);
        }
    }

    public void disconnect() {
        mqtt.disconnect();
    }

    public boolean isConnected() {
        return mqtt.isConnected();
    }

    public String getCallsign() { return callsign; }
    public BeaconConfig getBeaconConfig() { return beaconConfig; }
    public BroadcastConfig getBroadcastConfig() { return broadcastConfig; }
}
