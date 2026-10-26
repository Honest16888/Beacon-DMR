package net.beacon.dmr;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * DMR ID ⇄ 呼号 查询（RadioID.net 官方库）。
 *
 * <p><b>为什么必须在原生侧做</b>：页面跑在 {@code https://appassets.androidplatform.net}
 * 这个真实 https 源上，所以 {@code fetch} 跨域要过 CORS。而
 * {@code database.radioid.net} **不返回 Access-Control-Allow-Origin**
 * （实测只有 nginx 的默认安全头），网页里直接 fetch 会被浏览器拦掉。
 * 原生 HTTP 没有这条限制，所以查询放在 Java 侧，查完再把结果抛回页面。
 *
 * <p>接口本身不需要 key（响应头里只有一句 {@code X-RadioID-API-Key: recommended}），
 * 但建议控制调用频率 —— 所以只在用户手动「添加屏蔽」时调一次，结果由页面缓存。
 *
 * <p>两个方向都支持：
 * <ul>
 *   <li>{@code ?callsign=BH6RGQ} → 取 {@code id}</li>
 *   <li>{@code ?id=4614187} → 取 {@code callsign}</li>
 * </ul>
 */
public final class DmrDirectory {

    private static final String API = "https://database.radioid.net/api/dmr/user/";
    private static final int TIMEOUT_MS = 7000;

    private DmrDirectory() {
    }

    /**
     * 查询。
     *
     * @param q 呼号或纯数字 DMR ID
     * @return {@code "id|callsign"}；查不到或出错返回空串
     */
    public static String lookup(String q) {
        String key = q == null ? "" : q.trim();
        if (key.isEmpty()) return "";

        boolean byId = key.matches("\\d{1,9}");
        String url = API + (byId ? "?id=" : "?callsign=") + encode(key);

        String body = get(url);
        if (body == null) return "";

        try {
            JSONObject o = new JSONObject(body);
            JSONArray arr = o.optJSONArray("results");
            if (arr == null || arr.length() == 0) return "";

            /* 呼号查询可能命中多条（同一人多台设备、带后缀的呼号）。
             * 优先取呼号完全相等的那条，否则退回第一条。 */
            JSONObject best = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r == null) continue;
                if (best == null) best = r;
                String call = r.optString("callsign", "");
                if (!byId && call.equalsIgnoreCase(key)) {
                    best = r;
                    break;
                }
            }
            if (best == null) return "";

            int id = best.optInt("id", 0);
            if (id <= 0) id = best.optInt("radio_id", 0);
            String call = best.optString("callsign", "");
            if (id <= 0) return "";
            return id + "|" + call;
        } catch (Exception e) {
            return "";
        }
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** 一次 GET，成功返回响应体，失败返回 null */
    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/json");
            /* 有些网络环境对没 UA 的请求直接掐断 */
            c.setRequestProperty("User-Agent", "BeaconDMR/1.1 (Android)");
            int code = c.getResponseCode();
            if (code != 200) return null;

            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                /* 单个用户记录也就几百字节，超过 256 KB 一定是出问题了 */
                if (total > 256 * 1024) break;
                bo.write(buf, 0, n);
            }
            in.close();
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
