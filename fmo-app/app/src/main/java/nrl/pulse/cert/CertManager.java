package nrl.pulse.cert;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;
import java.io.File;

/**
 * 证书管理器 —— 管理 FMO 的 4 个证书文件。
 *
 * 从混淆类反编译确认的结构：
 *   certs/
 *   ├── index.json              证书索引（CertIndex）
 *   ├── cert_user.json          用户证书（subject.callsign, subject.uid, exp, publicKey, signature）
 *   ├── cert_devicekey.json     设备私钥（seed = 私钥种子）
 *   ├── cert_int.json           中级证书（builtin_certs/ 也有内置副本）
 *   └── cert_root.json          根证书
 *
 * 签发流程（从 dex 字符串确认）：
 *   1. 本地生成 ECDSA P-256 密钥对（seed → KeyPair）
 *   2. 生成 PKCS#10 CSR（Certificate Signing Request）
 *   3. 调用 /api/cert/bind 提交 CSR
 *   4. 服务端用 SHA256 签名，返回 cert_user + cert_int + cert_root
 *
 * 校验链：
 *   cert_root + cert_int → 验证 cert_user 签名 → cert_devicekey 私钥匹配
 */
public class CertManager {

    private static final String TAG = "CertManager";
    private static final String CERT_DIR = "certs";
    private final Context ctx;

    public CertManager(Context ctx) {
        this.ctx = ctx;
    }

    private File certDir() {
        File dir = new File(ctx.getFilesDir(), CERT_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 读取证书文件（原始内容） */
    public String readCert(String name) {
        File f = new File(certDir(), name);
        if (!f.exists()) return null;
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "read " + name, e);
            return null;
        }
    }

    /** 保存证书文件 */
    public void saveCert(String name, String content) {
        try {
            File f = new File(certDir(), name);
            java.nio.file.Files.write(f.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Log.i(TAG, "saved: " + name);
        } catch (Exception e) {
            Log.e(TAG, "save " + name, e);
        }
    }

    /** 检查所有证书是否齐全 */
    public boolean hasAll() {
        return readCert("cert_user.json") != null
            && readCert("cert_devicekey.json") != null
            && readCert("cert_int.json") != null
            && readCert("cert_root.json") != null;
    }

    /** 获取用户证书的呼号 */
    public String getCallsign() {
        try {
            JSONObject o = new JSONObject(readCert("cert_user.json"));
            JSONObject subject = o.optJSONObject("subject");
            return subject != null ? subject.optString("callsign") : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 获取用户证书的 UID */
    public String getUid() {
        try {
            JSONObject o = new JSONObject(readCert("cert_user.json"));
            JSONObject subject = o.optJSONObject("subject");
            return subject != null ? subject.optString("uid") : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 获取证书过期时间（Unix 秒） */
    public long getExpiry() {
        try {
            JSONObject o = new JSONObject(readCert("cert_user.json"));
            return o.optLong("exp", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 检查证书是否过期 */
    public boolean isExpired() {
        long exp = getExpiry();
        return exp > 0 && exp * 1000 < System.currentTimeMillis();
    }

    /** 获取证书剩余天数 */
    public long getDaysRemaining() {
        long exp = getExpiry();
        if (exp == 0) return -1;
        return (exp * 1000 - System.currentTimeMillis()) / (1000 * 86400);
    }

    /** 校验证书链（简化版） */
    public boolean verifyChain() {
        if (!hasAll()) return false;
        if (isExpired()) return false;
        // TODO: 用 BouncyCastle 验证 cert_int + cert_root 对 cert_user 的签名
        return true;
    }

    /** 从 JSON 文件里取 PEM 字段 */
    public String getPemField(String name) {
        String raw = readCert(name);
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            return o.optString("pem", raw);
        } catch (Exception e) {
            return raw;
        }
    }

    /** 获取私钥 seed（cert_devicekey.json） */
    public String getDeviceSeed() {
        try {
            JSONObject o = new JSONObject(readCert("cert_devicekey.json"));
            return o.optString("seed");
        } catch (Exception e) {
            return null;
        }
    }

    /** 获取证书公钥 */
    public String getPublicKey() {
        try {
            JSONObject o = new JSONObject(readCert("cert_user.json"));
            return o.optString("publicKey");
        } catch (Exception e) {
            return null;
        }
    }

    /** 获取证书签名 */
    public String getSignature() {
        try {
            JSONObject o = new JSONObject(readCert("cert_user.json"));
            return o.optString("signature");
        } catch (Exception e) {
            return null;
        }
    }
}
