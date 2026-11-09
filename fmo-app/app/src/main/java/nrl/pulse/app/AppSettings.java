package nrl.pulse.app;

/**
 * 应用配置 —— 对应 OpenRF 的 AppSettings（Kotlinx 序列化）。
 *
 * 从 dex 里的字段名推断：
 *   hubUrl: String          MQTT broker 地址
 *   callsign: String        呼号
 *   beacon: BeaconConfig    信标配置
 *   broadcast: BroadcastConfig  广播配置
 *   ssid: String            多设备区分
 */
public class AppSettings {
    public String hubUrl = "";
    public String callsign = "";
    public String ssid = "";
    public boolean autoConnect = true;
    public boolean keepAlive = true;
}
