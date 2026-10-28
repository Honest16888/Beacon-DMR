package net.beacon.dmr.live;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 主站表：主站 ID → 国家代码。
 *
 * <p>看板里「发信方主站分布」要显示成 {@code M4602 中国} 而不是光秃秃的
 * {@code M4602}，靠的就是这张表。数据来自
 * {@code https://api.brandmeister.network/v2/master}，40 条左右，
 * 格式是 {@code [{"id":4602,"country":"CN","address":"43.129.83.124"}, ...]}。
 *
 * <p>体积只有 2 KB，缓存 7 天。拉不到就用缓存，缓存也没有就退化成只显示主站号 ——
 * 看板不能因为一张辅助表就空着。
 */
public final class MasterRepo {

    private static final String TAG = "MasterRepo";
    private static final String URL = "https://api.brandmeister.network/v2/master";
    private static final String CACHE = "masters.tsv";
    private static final long TTL_MS = 7L * 24 * 3600 * 1000;
    private static final int TIMEOUT_MS = 12000;

    /** master id → ISO 大写（CN / DE / US …）。 */
    private static volatile Map<String, String> map = new HashMap<>();

    private MasterRepo() {}

    public static String iso(String masterId) {
        if (masterId == null) return null;
        return map.get(masterId);
    }

    public static int size() { return map.size(); }

    /**
     * 所有主站号，升序。
     *
     * <p>给「发射条上的主站下拉」用 —— 那边需要的是「有哪些主站可选」，
     * 而 {@link #iso} 只能反查单个。返回副本，调用方随便改。
     */
    public static java.util.List<String> ids() {
        java.util.List<String> l = new java.util.ArrayList<>(map.keySet());
        java.util.Collections.sort(l);
        return l;
    }

    /** 立刻读缓存（没有就空表），然后后台刷新。回调在主线程之外，调用方自己切。 */
    public static void load(Context ctx, Runnable onReady) {
        Map<String, String> cached = readCache(ctx);
        if (!cached.isEmpty()) map = cached;
        if (onReady != null) onReady.run();

        File f = new File(ctx.getFilesDir(), CACHE);
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < TTL_MS
                && !cached.isEmpty()) {
            return;
        }
        Thread t = new Thread(() -> {
            Map<String, String> m = fetch();
            if (m.isEmpty()) return;
            map = m;
            writeCache(ctx, m);
            if (onReady != null) onReady.run();
        }, "master-fetch");
        t.setDaemon(true);
        t.start();
    }

    private static Map<String, String> fetch() {
        Map<String, String> out = new HashMap<>();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(URL).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "BeaconDMR-Java/3.0");
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) {
                Log.w(TAG, "主站表 HTTP " + c.getResponseCode());
                return out;
            }
            StringBuilder sb = new StringBuilder(4096);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    c.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            }
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                String cc = o.optString("country", "");
                if (id.isEmpty() || cc.isEmpty()) continue;
                out.put(id, cc.toUpperCase(Locale.ROOT));
            }
        } catch (Exception e) {
            Log.w(TAG, "主站表拉取失败：" + e);
        } finally {
            if (c != null) c.disconnect();
        }
        return out;
    }

    private static Map<String, String> readCache(Context ctx) {
        Map<String, String> out = new HashMap<>();
        File f = new File(ctx.getFilesDir(), CACHE);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] p = line.split("\t");
                if (p.length < 2) continue;
                out.put(p[0].trim(), p[1].trim().toUpperCase(Locale.ROOT));
            }
        } catch (Exception e) {
            Log.w(TAG, "主站表缓存读失败", e);
        }
        return out;
    }

    private static void writeCache(Context ctx, Map<String, String> m) {
        StringBuilder sb = new StringBuilder(m.size() * 12);
        sb.append("# 主站表缓存，id<TAB>国家码\n");
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        try (FileOutputStream o = new FileOutputStream(new File(ctx.getFilesDir(), CACHE))) {
            o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "主站表缓存写失败", e);
        }
    }
}
