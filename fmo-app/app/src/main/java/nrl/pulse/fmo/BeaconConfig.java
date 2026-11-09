package nrl.pulse.fmo;

/**
 * 信标配置 —— 对应 OpenRF 的 BeaconConfig（Kotlinx 序列化）。
 *
 * 从混淆类反编译确认的 APRS 帧类型（4 种）：
 *   APFMO0,TCPIP*::  消息帧（文本消息）
 *   APFMO1,TCPIP*>:  状态帧（状态报告）
 *   APFMO2,TCPIP*>:  消息帧（个性化消息，如 fmoapp）
 *   APFMO4,TCPIP*:=  位置帧（经纬度 + 电台信息）
 *
 * 位置帧附加字段（从 dex 字符串提取）：
 *   ANT:   天线型号
 *   F      频率标记
 *   FREQ:  频率（MHz）
 *   HEIGHT: 天线高度（米）
 *   LA     纬度标记
 *   P      位置标记
 *   RIG:   电台型号
 *   S      信号标记
 *   U      用户标记
 *   KM,U   APRS 位置格式（公里，用户）
 *
 * 限制：信标报文最大 512 字节（APRS 官方限制）。
 * 防抖：距上次信标不足 N 秒不允许重发。
 */
public class BeaconConfig {
    public boolean enabled = false;
    public String callsign = "";
    public double lat, lon;
    public double freq;          // 频率 MHz
    public double height;        // 天线高度 米
    public String rig = "";      // 电台型号
    public String ant = "";      // 天线型号
    public String comment = "";

    /** 生成 APRS 位置帧（APFMO4） */
    public String toAprsPosition() {
        // 标准 APRS 位置：=DDMM.mmN/DDDMM.mmE>
        String latStr = formatAprsCoord(lat, true);
        String lonStr = formatAprsCoord(lon, false);
        return "= " + latStr + "/" + lonStr + ">";
    }

    /** 生成 APRS 信标帧（iFMO-APP,BEACON） */
    public String toAprsBeacon() {
        StringBuilder sb = new StringBuilder();
        sb.append(">iFMO-APP,BEACON");
        if (freq > 0) sb.append(",FREQ:").append(String.format("%.3f", freq));
        if (height > 0) sb.append(",HEIGHT:").append((int)height);
        if (rig != null && !rig.isEmpty()) sb.append(",RIG:").append(rig);
        if (ant != null && !ant.isEmpty()) sb.append(",ANT:").append(ant);
        return sb.toString();
    }

    /** 生成 APRS 个性化消息（APFMO2） */
    public String toAprsMessage() {
        return ">APFMO2,TCPIP*>:fmoapp";
    }

    /** 生成 APRS 状态帧（APFMO1） */
    public String toAprsStatus() {
        return ">APFMO1,TCPIP*:" + (comment != null ? comment : "");
    }

    /** 生成完整的 APRS 信标报文（位置 + 附加信息） */
    public String toAprsFull() {
        String pos = toAprsPosition();
        String beacon = toAprsBeacon();
        String msg = pos + beacon;
        if (msg.length() > 512) {
            msg = msg.substring(0, 512);
        }
        return msg;
    }

    /** APRS 坐标格式：DDMM.mmN / DDDMM.mmE */
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

    public String toJson() {
        return String.format(
            "{\"enabled\":%b,\"callsign\":\"%s\",\"lat\":%.6f,\"lon\":%.6f," +
            "\"freq\":%.3f,\"height\":%.1f,\"rig\":\"%s\",\"ant\":\"%s\",\"comment\":\"%s\"}",
            enabled, callsign, lat, lon, freq, height, rig, ant, comment);
    }

    /** MQTT topic */
    public String toTopic() {
        return "fmo/beacon/" + callsign + "/tx";
    }
}
