package nrl.pulse.app;

/**
 * DMR 凭据 —— 对应 OpenRF 的 DmrCredential（Kotlinx 序列化）。
 *
 * 从 dex 里的字段名推断：
 *   host: String        主站地址
 *   port: Int           端口
 *   callsign: String    呼号
 *   password: String    密码
 *   dmrId: Int          DMR ID
 */
public class DmrCredential {
    public String host = "";
    public int port = 62031;
    public String callsign = "";
    public String password = "";
    public int dmrId = 0;
}
