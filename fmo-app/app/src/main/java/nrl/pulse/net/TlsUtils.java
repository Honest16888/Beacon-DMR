package nrl.pulse.net;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * mTLS 工具：从 PEM 字符串构建 SSLContext。
 *
 * OpenRF 的证书体系（4 个文件）：
 *   cert_user.json       用户证书（PEM）
 *   cert_devicekey.json  设备私钥（PEM）
 *   cert_int.json        中级证书（PEM）
 *   cert_root.json       根证书（PEM）
 *
 * 这里把它们装进 KeyStore，交给 SSLContext 做双向认证。
 */
public class TlsUtils {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public static SSLContext createSslContext(String certPem, String keyPem, String caPem) throws Exception {
        // 1) 加载用户证书 + 私钥
        X509Certificate userCert = parseCertificate(certPem);
        PrivateKey privateKey = parsePrivateKey(keyPem);

        KeyStore clientKs = KeyStore.getInstance("PKCS12");
        clientKs.load(null, null);
        clientKs.setKeyEntry("device", privateKey, "".toCharArray(),
            new java.security.cert.Certificate[]{ userCert });

        // 2) 加载 CA 证书链
        List<X509Certificate> caCerts = parseCertificates(caPem);
        KeyStore trustKs = KeyStore.getInstance("PKCS12");
        trustKs.load(null, null);
        for (int i = 0; i < caCerts.size(); i++) {
            trustKs.setCertificateEntry("ca-" + i, caCerts.get(i));
        }

        // 3) 构建 SSLContext
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientKs, "".toCharArray());

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustKs);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    public static X509Certificate parseCertificate(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(pem.getBytes()));
    }

    public static List<X509Certificate> parseCertificates(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (List<X509Certificate>) cf.generateCertificates(new ByteArrayInputStream(pem.getBytes()));
    }

    public static PrivateKey parsePrivateKey(String pem) throws Exception {
        PEMParser parser = new PEMParser(new StringReader(pem));
        Object obj = parser.readObject();
        parser.close();
        if (obj instanceof PEMKeyPair) {
            return new JcaPEMKeyConverter().getKeyPair((PEMKeyPair) obj).getPrivate();
        }
        throw new IllegalArgumentException("无法解析私钥格式");
    }
}
