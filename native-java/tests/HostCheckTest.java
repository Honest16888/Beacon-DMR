import net.beacon.dmr.core.HostCheck;

/**
 * 主站地址校验的离线测试 —— 全部是真实踩过的写法。
 *
 * <p>这些用例的价值在于：每一个错误写法在界面上都只会表现成
 * {@code Load failed : Host not found}，看不出是哪一个字写错了。
 * 钉在桌面上，以后改 {@link HostCheck} 才不会把某一条放过去。
 */
public class HostCheckTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("=== 1. 合法地址 ===");
        ok("4602.master.brandmeister.network");
        ok("4601.master.brandmeister.network");
        ok("4602.MASTER.BRANDMEISTER.NETWORK");      // 大小写不敏感
        ok("  4602.master.brandmeister.network  ");   // 前后空格由 trim 吃掉
        ok("43.129.83.124");                          // 直接填 IP
        ok("127.0.0.1");
        ok("localhost");
        ok("hblink.example.com");                     // 自建主站，不能拦
        ok("mmdvm.lan");

        System.out.println("\n=== 2. 真机上的那个地址 ===");
        /* 这是用户报告里原样的一行。数字 4614 插进了 master 中间，
         * 而且 4614 正好是 DMR ID 4614187 的前四位。 */
        bad("4602.ma4614ster.brandmeister.network", "4602.master.brandmeister.network");

        System.out.println("\n=== 3. BrandMeister 域名写歪 ===");
        bad("4602.brandmeister.network", "4602.master.brandmeister.network");
        bad("master.brandmeister.network", null);      // 没主站号，猜不出来
        bad("4602.master.brandmeister.net", null);     // 走不到 BM 分支，当普通域名放行？
        bad("foo.master.brandmeister.network", null);  // 前缀不是数字

        System.out.println("\n=== 4. 只填了主站号 ===");
        bad("4602", "4602.master.brandmeister.network");
        bad("4602147", "4602147.master.brandmeister.network");

        System.out.println("\n=== 5. 全角 / 中文 ===");
        bad("4602．master.brandmeister.network", null);   // 全角句号
        bad("４６０２.master.brandmeister.network", null); // 全角数字
        bad("中国.master.brandmeister.network", null);

        System.out.println("\n=== 6. 空格与非法字符 ===");
        bad2("4602 .master.brandmeister.network", "4602.master.brandmeister.network");
        bad("4602/master.brandmeister.network", null);
        bad("4602_master.brandmeister.network", null);    // 下划线不许进主机名
        bad(".4602.master.brandmeister.network", null);
        bad("4602.master.brandmeister.network.", null);
        bad2("4602..master.brandmeister.network", "4602.master.brandmeister.network");

        System.out.println("\n=== 7. 空 ===");
        bad("", null);
        bad(null, null);
        bad("   ", null);

        System.out.println("\n=== 8. IP 越界 ===");
        bad("43.129.83.256", null);
        bad("999.1.1.1", null);

        System.out.println("\n=== 9. 段首尾连字符 ===");
        bad("-4602.master.brandmeister.network", null);
        bad("4602-.master.brandmeister.network", null);

        System.out.println("\n=== 10. 猜主站号 ===");
        eq("4602.master.brandmeister.network",
                HostCheck.guessBm("4602.ma4614ster.brandmeister.network"));
        eq("4602.master.brandmeister.network",
                HostCheck.guessBm("4602.brandmeister.network"));
        eq("4601.master.brandmeister.network", HostCheck.guessBm("4601"));
        /* 没有 4 位数字时退而取第一段数字 —— 猜错也总比原来那串强，
         * 而且用户会看到建议、自己决定要不要用。 */
        eq("46.master.brandmeister.network", HostCheck.guessBm("46.master.brandmeister.network"));
        eq(null, HostCheck.guessBm("master.brandmeister.network"));

        System.out.println("\n=== 11. normalize ===");
        eq("4602.master.brandmeister.network",
                HostCheck.normalize("  4602 . MASTER.brandmeister.network "));
        eq("", HostCheck.normalize(null));

        System.out.println("\n=== 12. 结果对象自洽 ===");
        HostCheck.Result r = HostCheck.check("4602.master.brandmeister.network");
        check("OK 的 ok() 为 true", r.ok());
        check("OK 的 bad() 为 false", !r.bad());
        check("OK 时 message 为空", r.message.isEmpty());
        HostCheck.Result w = HostCheck.check("460.master.brandmeister.network");
        check("3 位前缀给 WARN", w.level == HostCheck.Level.WARN);
        check("WARN 不算 bad", !w.bad());
        check("WARN 也不算 ok", !w.ok());

        System.out.printf("\n================  %d 通过 / %d 失败  ================%n",
                pass, fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 断言

    /** 期望 OK。 */
    private static void ok(String host) {
        HostCheck.Result r = HostCheck.check(host);
        boolean good = r.level == HostCheck.Level.OK;
        report(good, "OK", host, r.toString());
    }

    /** 期望 BAD，且（给了的话）建议值等于 want。 */
    private static void bad(String host, String want) {
        HostCheck.Result r = HostCheck.check(host);
        if (r.level != HostCheck.Level.BAD) {
            report(false, "BAD", host, r.toString());
            return;
        }
        if (want == null) {
            pass++;
            System.out.println("  ✓ BAD  " + n(host));
            return;
        }
        boolean m = want.equals(r.suggestion);
        report(m, "BAD → " + want, host,
                "实际建议 " + r.suggestion + "  （" + r.message + "）");
    }

    /** 期望 BAD，且建议值等于 want（拒绝时必须给出可替换值）。 */
    private static void bad2(String host, String want) {
        bad(host, want);
    }

    private static void eq(Object want, Object got) {
        boolean m = (want == null) ? got == null : want.equals(got);
        String line = n(String.valueOf(want)) + " ← " + n(String.valueOf(got));
        if (m) {
            pass++;
            System.out.println("  ✓ " + line);
        } else {
            fail++;
            System.out.println("  ✗ " + line);
        }
    }

    private static void check(String what, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  ✓ " + what);
        } else {
            fail++;
            System.out.println("  ✗ " + what);
        }
    }

    private static void report(boolean good, String what, String input, String detail) {
        if (good) {
            pass++;
            System.out.println("  ✓ " + what + "  " + n(input));
        } else {
            fail++;
            System.out.println("  ✗ " + what + "  " + n(input) + "   →  " + detail);
        }
    }

    private static String n(String s) {
        return s == null ? "(null)" : "\"" + s + "\"";
    }
}
