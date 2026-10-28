import net.beacon.dmr.core.TalkgroupRepo;
import net.beacon.dmr.core.Talkgroups;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 话组表合并规则自检。
 *
 * <p>只测 {@link TalkgroupRepo#merge} —— 它是纯函数（List + Map 进，List 出），
 * 不碰 Context。为了让这个类能编译，本测试用 Android SDK 的 android.jar 做
 * classpath（见 tests/run.sh），但**不会执行任何需要 Context 的代码**。
 *
 * <p>规则很重要：内置表里的中文名不能被在线表的英文名覆盖。
 * 91 必须还是「世界组」，不能变成 "World-wide" —— 这是用户能立刻看出来的差别。
 */
public final class TgMergeTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, Object detail) {
        if (cond) { pass++; System.out.println("  \u2713 " + name); }
        else {
            fail++;
            System.out.println("  \u2717 " + name + (detail == null ? "" : "  \u2192 " + detail));
        }
    }

    private static Talkgroups.Tg tg(int id, String n, String d) { return new Talkgroups.Tg(id, n, d); }

    public static void main(String[] args) {
        System.out.println("\n=== 5. 话组表合并（内置优先）===");

        List<Talkgroups.Tg> local = new ArrayList<>();
        local.add(tg(9, "本地组", "Local"));
        local.add(tg(91, "世界组", "World-wide"));
        local.add(tg(460, "中国", "China"));

        Map<Integer, String> online = new LinkedHashMap<>();
        online.put(91, "World-wide");          // 冲突：应保留中文名
        online.put(460, "China");              // 冲突：应保留中文名
        online.put(2350, "United Kingdom");    // 新增
        online.put(9999, "Something New");     // 新增
        online.put(3100, "USA Bridge");        // 新增

        List<Talkgroups.Tg> m = TalkgroupRepo.merge(local, online);

        ok("总数 = 3 本地 + 3 新增", m.size() == 6, m.size());
        ok("91 仍是中文名「世界组」", "世界组".equals(nameOf(m, 91)), nameOf(m, 91));
        ok("460 仍是中文名「中国」", "中国".equals(nameOf(m, 460)), nameOf(m, 460));
        ok("本地顺序不变（前 3 项）",
                m.get(0).id == 9 && m.get(1).id == 91 && m.get(2).id == 460,
                m.get(0).id + "," + m.get(1).id + "," + m.get(2).id);
        ok("新增项按 id 升序",
                m.get(3).id == 2350 && m.get(4).id == 3100 && m.get(5).id == 9999,
                m.get(3).id + "," + m.get(4).id + "," + m.get(5).id);
        ok("新增项没有重复的 91/460", count(m, 91) == 1 && count(m, 460) == 1,
                count(m, 91) + "/" + count(m, 460));
        ok("中文说明没有丢", "World-wide".equals(descOf(m, 91)), descOf(m, 91));

        // 在线表为空 / null：原样返回本地
        ok("在线表为 null 时返回本地表", TalkgroupRepo.merge(local, null).size() == 3, null);
        ok("在线表为空 Map 时返回本地表",
                TalkgroupRepo.merge(local, new LinkedHashMap<>()).size() == 3, null);

        // 本地为空：全用在线
        List<Talkgroups.Tg> onlyOnline = TalkgroupRepo.merge(new ArrayList<>(), online);
        ok("本地为空时全部来自在线表", onlyOnline.size() == 5, onlyOnline.size());
        ok("本地为空时按 id 升序",
                onlyOnline.get(0).id == 91 && onlyOnline.get(4).id == 9999,
                onlyOnline.get(0).id + ".." + onlyOnline.get(4).id);

        // 在线表的空名字应该被跳过
        Map<Integer, String> withEmpty = new LinkedHashMap<>();
        withEmpty.put(5000, "");
        withEmpty.put(5001, "Real");
        List<Talkgroups.Tg> m2 = TalkgroupRepo.merge(new ArrayList<>(), withEmpty);
        ok("空名字的在线项被跳过", m2.size() == 1 && m2.get(0).id == 5001, m2.size());

        System.out.println("\n================  " + pass + " \u901a\u8fc7 / " + fail + " \u5931\u8d25  ================");
        if (fail > 0) System.exit(1);
    }

    private static String nameOf(List<Talkgroups.Tg> l, int id) {
        for (Talkgroups.Tg t : l) if (t.id == id) return t.name;
        return null;
    }

    private static String descOf(List<Talkgroups.Tg> l, int id) {
        for (Talkgroups.Tg t : l) if (t.id == id) return t.desc;
        return null;
    }

    private static int count(List<Talkgroups.Tg> l, int id) {
        int n = 0;
        for (Talkgroups.Tg t : l) if (t.id == id) n++;
        return n;
    }
}
