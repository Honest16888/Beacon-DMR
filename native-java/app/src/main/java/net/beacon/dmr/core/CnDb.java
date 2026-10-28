package net.beacon.dmr.core;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * 内嵌的呼号库 —— DMR ID → 呼号 / 姓名 / 地区。
 *
 * <p>两张表，都用 {@code assets/} 下的 TSV（格式 {@code ID:呼号:姓名:地区}）：
 * <ul>
 *   <li>{@code cn_db.tsv} —— BrandMeister 的中国台站（45/46 号段），<b>15,527 条</b>；
 *   <li>{@code cn86_db.tsv} —— radiowo / China DMR 的 86 号段，<b>1,642 条</b>
 *       （含呼号、姓名、城市，比 BM 那张更新）。
 * </ul>
 *
 * <p><b>为什么内嵌而不联网：</b>国内没有开放 CORS 的公开呼号接口
 * （qrz.cn / qrzworld / hamcq 都是网页表单），radioid.net 有全量数据但不返 CORS 头。
 * 而内嵌表零网络、零延迟、离线可用 —— 对一个「开机就在守听」的 App 来说，
 * 联网查一个呼号要等 300 ms 是荒谬的。
 *
 * <p>★ <b>两张表要先读 BM 再读 86，且都是 first-wins。</b>
 * WebView 版的原话：先读到的优先，所以 86 库不会覆盖 BM 库已有的记录。
 *
 * <p>★ <b>解析必须在后台线程。</b>21,000 行字符串切分，主线程做要 200~400 ms，
 * 正好卡在启动第一帧上 —— 表现是「点了图标，白屏半秒」。
 */
public final class CnDb {

    private static final String TAG = "CnDb";

    /** 一条台站记录。{@code src} 是 {@code "cn"}（BM 表）或 {@code "86"}（radiowo 表）。 */
    public static final class St {
        public final int id;
        public final String call;
        public final String name;
        public final String city;
        public final String src;

        St(int id, String call, String name, String city, String src) {
            this.id = id;
            this.call = call == null ? "" : call;
            this.name = name == null ? "" : name;
            this.city = city == null ? "" : city;
            this.src = src;
        }
    }

    private static volatile Map<Integer, St> byId;
    private static volatile Map<String, St> byCall;
    private static volatile boolean loading;
    private static volatile boolean ready;

    private CnDb() {
    }

    public static boolean isReady() {
        return ready;
    }

    public static int size() {
        Map<Integer, St> m = byId;
        return m == null ? 0 : m.size();
    }

    /** 按 DMR ID 查。没加载完或没这条都返回 null —— 调用方要能接住 null。 */
    public static St byId(int id) {
        Map<Integer, St> m = byId;
        return m == null ? null : m.get(id);
    }

    /** 按呼号查（大小写不敏感）。 */
    public static St byCall(String call) {
        if (call == null || call.isEmpty()) return null;
        Map<String, St> m = byCall;
        return m == null ? null : m.get(call.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * 加载（幂等）。**必须在后台线程调**，见类注释。
     *
     * @param onReady 加载完成后回调（在调用线程上，不是主线程）
     */
    public static synchronized void load(Context ctx, Runnable onReady) {
        if (ready) {
            if (onReady != null) onReady.run();
            return;
        }
        if (loading) return;
        loading = true;

        Map<Integer, St> ids = new HashMap<>(32000);
        Map<String, St> calls = new HashMap<>(32000);
        /* 顺序固定：先 BM 再 86，first-wins。反过来的话 86 那 1,642 条
         * 会盖掉 BM 里同 ID 的记录，而 BM 那张表字段更全。 */
        int a = loadBlock(ctx, "cn_db.tsv", "cn", ids, calls);
        int b = loadBlock(ctx, "cn86_db.tsv", "86", ids, calls);

        byId = ids;
        byCall = calls;
        ready = true;
        loading = false;
        Log.i(TAG, "呼号库就绪：" + ids.size() + " 条（cn " + a + " / 86 " + b + "）");
        if (onReady != null) onReady.run();
    }

    /**
     * @return 这个块贡献的<b>新</b>条目数（已被占用的 ID 不算）
     */
    private static int loadBlock(Context ctx, String file, String tag,
                                 Map<Integer, St> ids, Map<String, St> calls) {
        int n = 0;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(ctx.getAssets().open(file), "UTF-8"), 8192)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                /* 格式 ID:呼号:姓名:地区 —— 用 limit 4 是因为姓名里可能带冒号，
                 * 那种行宁可把冒号留在姓名里，也不要整行丢掉。 */
                String[] p = line.split(":", 4);
                if (p.length < 3) continue;
                int id;
                try {
                    id = Integer.parseInt(p[0].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                String call = p[1] == null ? "" : p[1].trim().toUpperCase(java.util.Locale.ROOT);
                if (id <= 0 || call.isEmpty()) continue;

                St st = new St(id, call,
                        p.length > 2 ? p[2].trim() : "",
                        p.length > 3 ? p[3].trim() : "",
                        tag);

                if (!ids.containsKey(id)) {
                    ids.put(id, st);
                    n++;
                }
                if (!calls.containsKey(call)) calls.put(call, st);
            }
        } catch (Exception e) {
            Log.w(TAG, "读 " + file + " 失败：" + e);
        }
        return n;
    }
}
