package nrl.pulse.cert;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.asn1.x500.X500Name;

import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * 证书签发管理器 —— 生成 ECDSA P-256 密钥对 + PKCS#10 CSR + 调用 /api/cert/bind。
 *
 * 流程（从 OpenRF 混淆类确认）：
 *   1. 生成 ECDSA P-256（secp256r1）密钥对
 *   2. 用 SHA256withECDSA 签名，生成 PKCS#10 CSR
 *   3. POST /api/cert/bind 提交 CSR（JSON: {"csr":"...","client_type":"app"}）
 *   4. 服务端返回 cert_user + cert_int + cert_root
 */
public class CertIssuer {

    private static final String TAG = "CertIssuer";

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /** 生成 ECDSA P-256 密钥对 */
    public static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }

    /**
     * 生成 PKCS#10 CSR（ASN.1 DER → Base64 PEM）。
     *
     * 结构：
     *   CertificationRequest ::= SEQUENCE {
     *     certificationRequestInfo  CertificationRequestInfo,
     *     signatureAlgorithm        AlgorithmIdentifier,
     *     signature                 BIT STRING
     *   }
     */
    public static String generateCsr(String callsign, String uid, KeyPair keyPair) throws Exception {
        // X.500 主题：CN=callsign, O=FMO, OU=uid
        X500Name subject = new X500Name("CN=" + callsign + ",O=FMO,OU=" + uid);

        // SHA256withECDSA 签名
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
            .setProvider("BC")
            .build(keyPair.getPrivate());

        // 构建 PKCS#10 CSR
        PKCS10CertificationRequest csr = new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic())
            .build(signer);

        // 导出 DER → Base64 PEM
        byte[] der = csr.getEncoded();
        return toPem("CERTIFICATE REQUEST", der);
    }

    /**
     * 提交 CSR 到 /api/cert/bind，换取证书。
     */
    public static JSONObject submitCsr(String apiUrl, String csrPem) throws Exception {
        String url = apiUrl + "/api/cert/bind";
        JSONObject body = new JSONObject();
        body.put("csr", csrPem);
        body.put("client_type", "app");

        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);
        conn.getOutputStream().write(body.toString().getBytes("UTF-8"));

        int code = conn.getResponseCode();
        Log.i(TAG, "cert/bind HTTP " + code);
        java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String respBody = "";
        if (in != null) {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            respBody = sb.toString();
        }
        Log.i(TAG, "cert/bind response [" + code + "]: " + respBody);

        if (code >= 400) {
            throw new RuntimeException("HTTP " + code + ": " + respBody);
        }
        return new JSONObject(respBody);
    }

    /**
     * 一键完成：生成密钥对 → CSR → 提交 → 保存证书。
     */
    public static boolean issueCertificate(Context ctx, String apiUrl,
                                            String callsign, String uid) {
        try {
            Log.i(TAG, "开始签发证书: " + callsign);

            // 1. 生成密钥对
            KeyPair kp = generateKeyPair();
            byte[] privBytes = kp.getPrivate().getEncoded();
            String seed = Base64.getEncoder().encodeToString(privBytes);
            String pubPem = toPem("PUBLIC KEY", kp.getPublic().getEncoded());

            // 2. 保存设备私钥
            JSONObject deviceKey = new JSONObject();
            deviceKey.put("seed", seed);
            saveJson(ctx, "cert_devicekey.json", deviceKey);
            Log.i(TAG, "密钥对已生成并保存");

            // 3. 生成 PKCS#10 CSR
            String csrPem = generateCsr(callsign, uid, kp);
            Log.i(TAG, "CSR 已生成: " + csrPem.substring(0, Math.min(100, csrPem.length())) + "...");

            // 4. 提交 CSR
            JSONObject result = submitCsr(apiUrl, csrPem);
            if (result == null) {
                Log.e(TAG, "CSR 提交失败");
                return false;
            }

            // 5. 保存证书
            String certUser = result.optString("cert_user", result.optString("certificate", ""));
            String certInt = result.optString("cert_int", "");
            String certRoot = result.optString("cert_root", "");

            if (certUser.isEmpty()) {
                Log.e(TAG, "响应里没有证书: " + result.toString());
                return false;
            }

            JSONObject certUserJson = new JSONObject();
            certUserJson.put("pem", certUser);
            JSONObject subject = new JSONObject();
            subject.put("callsign", callsign);
            subject.put("uid", uid);
            certUserJson.put("subject", subject);
            certUserJson.put("publicKey", pubPem);
            certUserJson.put("exp", System.currentTimeMillis() / 1000 + 365 * 86400);
            saveJson(ctx, "cert_user.json", certUserJson);

            if (!certInt.isEmpty()) {
                JSONObject o = new JSONObject();
                o.put("pem", certInt);
                saveJson(ctx, "cert_int.json", o);
            }
            if (!certRoot.isEmpty()) {
                JSONObject o = new JSONObject();
                o.put("pem", certRoot);
                saveJson(ctx, "cert_root.json", o);
            }

            Log.i(TAG, "证书签发成功: " + callsign);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "证书签发失败", e);
            return false;
        }
    }

    private static void saveJson(Context ctx, String name, JSONObject obj) throws Exception {
        java.io.File dir = new java.io.File(ctx.getFilesDir(), "certs");
        if (!dir.exists()) dir.mkdirs();
        java.io.File f = new java.io.File(dir, name);
        java.nio.file.Files.write(f.toPath(), obj.toString().getBytes("UTF-8"));
    }

    private static String toPem(String type, byte[] data) {
        String b64 = Base64.getEncoder().encodeToString(data);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append("\n");
        }
        sb.append("-----END ").append(type).append("-----\n");
        return sb.toString();
    }
}
