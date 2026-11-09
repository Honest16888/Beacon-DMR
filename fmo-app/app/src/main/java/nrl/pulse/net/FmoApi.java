package nrl.pulse.net;

import android.util.Log;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * FMO REST API 客户端。
 *
 * 从 OpenRF 的 dex 里挖出来的端点：
 *   /api/fmo/stations     FMO 电台列表
 *   /api/cert/bind        证书绑定/换发
 *   /api/login            登录
 *   /api/public/overview  公开目录
 *
 * 认证用 mTLS 证书（不是 Bearer token）。
 */
public class FmoApi {

    private static final String TAG = "FmoApi";
    private String baseUrl;

    public FmoApi(String baseUrl) {
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.example.com";
    }

    /** GET /api/fmo/stations —— 获取 FMO 电台列表 */
    public String getStations() {
        return httpGet("/api/fmo/stations");
    }

    /** GET /api/public/overview —— 公开目录 */
    public String getOverview() {
        return httpGet("/api/public/overview");
    }

    /** POST /api/cert/bind —— 绑定/换发证书 */
    public String bindCert(String csr, String challenge) {
        String body = String.format("{\"csr\":\"%s\",\"challenge\":\"%s\"}", csr, challenge);
        return httpPost("/api/cert/bind", body);
    }

    private String httpGet(String path) {
        try {
            URL url = new URL(baseUrl + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setRequestProperty("Accept", "application/json");
            return readResponse(conn);
        } catch (Exception e) {
            Log.e(TAG, "GET " + path, e);
            return null;
        }
    }

    private String httpPost(String path, String body) {
        try {
            URL url = new URL(baseUrl + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            return readResponse(conn);
        } catch (Exception e) {
            Log.e(TAG, "POST " + path, e);
            return null;
        }
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        InputStream in = conn.getResponseCode() >= 400
            ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) return null;
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }
}
