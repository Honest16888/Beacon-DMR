package net.beacon.dmr.core;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * 话组表：内置 → 磁盘缓存 → 在线全表，三级来源。
 *
 * <p>为什么要三级：
 * <ul>
 *   <li><b>内置</b>（assets，47 条）：装完立刻能听，断网也能听。国内组的中文名
 *       只在这份里，官方在线表对 860xx 那些组根本没有记录。</li>
 *   <li><b>缓存</b>（filesDir）：上次拉到的 1800 多条。冷启动直接用它，
 *       不用等网络 —— 不然每次开 App 都要空着列表等十几秒。</li>
 *   <li><b>在线</b>：{@code api.brandmeister.network/v2/talkgroup}，1845 条左右，
 *       只有 id → 英文名。</li>
 * </ul>
 *
 * <p><b>合并规则：内置优先。</b> 同一个 id 两边都有时保留内置的中文名
 * （91 是「世界组」而不是 "World-wide"），在线表只补内置没有的那些。
 * 顺序也是内置在前、在线在后按 id 升序 —— 常用的永远在最上面，
 * 找冷门组用搜索。
 */
public final class TalkgroupRepo {

    private static final String TAG = "TgRepo";
    private static final String ONLINE_URL = "https://api.brandmeister.network/v2/talkgroup";
    private static final String CACHE_FILE = "talkgroups.cache.tsv";
    /** 缓存多久算过期。官方表变动不频繁，7 天足够，也避免每次开 App 都打接口。 */
    private static final long CACHE_TTL_MS = 7L * 24 * 3600 * 1000;
    private static final int TIMEOUT_MS = 12000;

    /** 来源：内置表（assets）。 */
    public static final int SRC_BUILTIN = 0;
    /** 来源：磁盘缓存（上次拉到在线表的副本）。 */
    public static final int SRC_CACHE = 1;
    /** 来源：在线全表。 */
    public static final int SRC_ONLINE = 2;
    /** 在线表没拉到。此时回调里的表就是「内置 + 缓存」，别拿它覆盖已有列表。 */
    public static final int SRC_FAIL = 3;

    public interface Callback {
        /**
         * @param all    合并后的全表
         * @param source {@link #SRC_BUILTIN} / {@link #SRC_CACHE} / {@link #SRC_ONLINE}
         *               / {@link #SRC_FAIL}
         */
        void onReady(List<Talkgroups.Tg> all, int source);
    }

    private TalkgroupRepo() {}

    /* ==================== 内置 ==================== */

    public static List<Talkgroups.Tg> loadBuiltin(Context ctx) {
        List<Talkgroups.Tg> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("talkgroups.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] p = line.split("\t");
                if (p.length < 2) continue;
                int id;
                try { id = Integer.parseInt(p[0].trim()); } catch (NumberFormatException e) { continue; }
                out.add(new Talkgroups.Tg(id, p[1].trim(), p.length > 2 ? p[2].trim() : ""));
            }
        } catch (Exception e) {
            Log.w(TAG, "内置话组表读不出来", e);
        }
        if (out.isEmpty()) {
            /* 连 assets 都读不到（不该发生）时给一个最小可用集合，
             * 总比给用户一个空列表强。 */
            out.add(new Talkgroups.Tg(91, "世界组", "World-wide"));
            out.add(new Talkgroups.Tg(460, "中国", "China"));
        }
        return out;
    }

    /* ==================== 缓存 ==================== */

    private static File cacheFile(Context ctx) { return new File(ctx.getFilesDir(), CACHE_FILE); }

    /** 读缓存。没有或已过期返回空表（过期也照读，只是会触发一次刷新）。 */
    public static List<Talkgroups.Tg> loadCache(Context ctx) {
        List<Talkgroups.Tg> out = new ArrayList<>();
        File f = cacheFile(ctx);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] p = line.split("\t");
                if (p.length < 2) continue;
                int id;
                try { id = Integer.parseInt(p[0].trim()); } catch (NumberFormatException e) { continue; }
                out.add(new Talkgroups.Tg(id, p[1].trim(), ""));
            }
        } catch (Exception e) {
            Log.w(TAG, "缓存读失败", e);
        }
        return out;
    }

    private static boolean cacheFresh(Context ctx) {
        File f = cacheFile(ctx);
        return f.exists() && System.currentTimeMillis() - f.lastModified() < CACHE_TTL_MS;
    }

    private static void saveCache(Context ctx, Map<Integer, String> online) {
        StringBuilder sb = new StringBuilder(online.size() * 24);
        sb.append("# 在线话组表缓存，id<TAB>英文名\n");
        for (Map.Entry<Integer, String> e : online.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue().replace('\t', ' ')).append('\n');
        }
        File f = cacheFile(ctx);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "缓存写失败", e);
        }
    }

    /* ==================== 在线 ==================== */

    /** 拉在线表。阻塞，必须放在后台线程。失败返回 null。 */
    public static Map<Integer, String> fetchOnline() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(ONLINE_URL).openConnection();
            /* 不用另外设 SSL 工厂：HttpsURLConnection 默认就走系统信任链，
             * 和浏览器的行为一致。显式设一遍反而多一个 ClassCast 的坑。 */
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "BeaconDMR-Java/3.0");
            c.setUseCaches(false);

            int code = c.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "话组表 HTTP " + code);
                return null;
            }
            byte[] body = readAll(c.getInputStream());
            JSONObject j = new JSONObject(new String(body, StandardCharsets.UTF_8));
            Map<Integer, String> out = new LinkedHashMap<>();
            Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next();
                String name = j.optString(k, "");
                int id;
                try { id = Integer.parseInt(k.trim()); } catch (NumberFormatException e) { continue; }
                /* 官方表里还有 1、2、8 这种「Local / Cluster / Regional」的占位项，
                 * 小于 90 的一律不要 —— WebView 版也是这么过滤的。 */
                if (id <= 90) continue;
                if (name.isEmpty() || "null".equals(name)) continue;
                out.put(id, name);
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "话组表拉取失败：" + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in; ByteArrayOutputStream o = new ByteArrayOutputStream(65536)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    /* ==================== 合并 ==================== */

    /**
     * 内置优先合并。内置里有的 id 保留中文名与说明，在线表只补空缺。
     * 结果顺序 = 内置顺序 + 在线补充（按 id 升序）。
     */
    public static List<Talkgroups.Tg> merge(List<Talkgroups.Tg> local, Map<Integer, String> online) {
        List<Talkgroups.Tg> out = new ArrayList<>(local.size() + (online == null ? 0 : online.size()));
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Talkgroups.Tg t : local) {
            out.add(t);
            seen.add(t.id);
        }
        if (online != null && !online.isEmpty()) {
            List<Integer> extra = new ArrayList<>();
            for (Integer id : online.keySet()) if (!seen.contains(id)) extra.add(id);
            Collections.sort(extra);
            for (Integer id : extra) {
                String n = online.get(id);
                if (n == null || n.isEmpty()) continue;
                out.add(new Talkgroups.Tg(id, n, ""));
            }
        }
        return out;
    }

    /* ==================== 对外入口 ==================== */

    /**
     * 立刻用「内置 + 缓存」给出一份可用的表，然后在后台刷新，
     * 拉到了再回调一次。调用方拿到的两次结果都可能是最终结果，
     * 只是第二次更全 —— 不要假设只回调一次。
     */
    public static void load(Context ctx, Callback cb) {
        List<Talkgroups.Tg> builtin = loadBuiltin(ctx);
        List<Talkgroups.Tg> cached = loadCache(ctx);
        final List<Talkgroups.Tg> first = merge(builtin, toMap(cached));
        cb.onReady(first, cached.isEmpty() ? SRC_BUILTIN : SRC_CACHE);

        if (cacheFresh(ctx) && !cached.isEmpty()) return;   // 缓存还新鲜，不折腾网络

        Thread t = new Thread(() -> {
            Map<Integer, String> online = fetchOnline();
            if (online == null || online.isEmpty()) {
                /* 明确告诉调用方「拉失败了」——否则界面永远停在「内置 47」，
                 * 用户只会说「组不全」，而真正的原因在网络上。 */
                cb.onReady(first, SRC_FAIL);
                return;
            }
            saveCache(ctx, online);
            List<Talkgroups.Tg> full = merge(builtin, online);
            cb.onReady(full, SRC_ONLINE);
        }, "tg-fetch");
        t.setDaemon(true);
        t.start();
    }

    private static Map<Integer, String> toMap(List<Talkgroups.Tg> list) {
        Map<Integer, String> m = new LinkedHashMap<>();
        for (Talkgroups.Tg t : list) m.put(t.id, t.name);
        return m;
    }
}
