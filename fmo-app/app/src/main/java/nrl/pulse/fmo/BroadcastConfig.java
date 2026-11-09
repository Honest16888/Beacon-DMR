package nrl.pulse.fmo;

/**
 * 广播配置 —— 对应 OpenRF 的 BroadcastConfig（Kotlinx 序列化）。
 *
 * 从混淆类反编译确认的字段：
 *   enabled, modeMin, text
 *
 * 广播用 APRS 协议发射：
 *   帧头：>iFMO-APP,STATION,CERT:<证书信息>
 *   位置：P（位置）、F（频率）、KM,U（APRS 位置格式）
 *
 * 限制：
 *   - MQTT 角色必须是 super 才能发服务器广播
 *   - 必须连接自己的服务器（服务器呼号 == 本机证书呼号）
 *   - 需要 cert_user.json + cert_devicekey.json
 */
public class BroadcastConfig {
    public boolean enabled = false;
    public int modeMin = 0;
    public String text = "";
    public String serverCallsign = "";  // 服务器呼号（必须等于本机证书呼号）

    /** 生成 APRS 广播帧 */
    public String toAprsBroadcast() {
        return ">iFMO-APP,STATION,CERT:" + text;
    }

    /** MQTT topic */
    public String toTopic() {
        return "fmo/broadcast/global/tx";
    }

    public String toJson() {
        return String.format(
            "{\"enabled\":%b,\"modeMin\":%d,\"text\":\"%s\",\"serverCallsign\":\"%s\"}",
            enabled, modeMin, text, serverCallsign);
    }
}
