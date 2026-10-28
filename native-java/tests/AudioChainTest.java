import net.beacon.dmr.audio.AudioChain;

/**
 * 音频链路自检 —— 桌面 JDK 直接跑，不需要设备。
 *
 * <p>这几条对应的是用户在真机上最容易抱怨的三件事：
 * <ul>
 *   <li><b>「声音发炸 / 噼啪」</b> —— 出口超过 ±1 被硬削波。这里直接断言
 *       把音量和增强都推到最大、喂满刻度方波，出口峰值也必须 ≤ 0.98。</li>
 *   <li><b>「开了增强反而变小声」</b> —— 软削波把正常语音也压了。
 *       这里断言 -20 dBFS 的小信号在 1× / 窄带关的情况下基本原样通过。</li>
 *   <li><b>「窄带增强听不出区别」</b> —— 2.6 kHz 那一段必须真的抬起来。</li>
 * </ul>
 *
 * <p>★ 为什么必须在这里测：这些量的差别只有几个百分点，手机喇叭上根本听不出来，
 * 但「有没有削波」是 0 和 1 的区别 —— 一旦错了就是满屏投诉。
 */
public final class AudioChainTest {

    private static final int FRAME = 160;        // 20 ms @ 8 kHz
    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, Object detail) {
        if (cond) { pass++; System.out.println("  \u2713 " + name); }
        else {
            fail++;
            System.out.println("  \u2717 " + name + (detail == null ? "" : "  \u2192 " + detail));
        }
    }

    /* 生成一帧正弦 */
    private static short[] sine(double freq, double amp, int n, int phaseOff) {
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            s[i] = (short) Math.round(amp * 32767 * Math.sin(2 * Math.PI * freq * (i + phaseOff) / 8000.0));
        }
        return s;
    }

    private static double peak(short[] s) {
        int m = 0;
        for (short v : s) m = Math.max(m, Math.abs(v));
        return m / 32767.0;
    }

    private static double rms(short[] s, int from) {
        double sum = 0;
        int n = 0;
        for (int i = from; i < s.length; i++) { sum += (double) s[i] * s[i]; n++; }
        return n == 0 ? 0 : Math.sqrt(sum / n) / 32767.0;
    }

    public static void main(String[] args) {
        System.out.println("\n=== 7. 音频链路（增益 / 窄带增强 / 软削波）===");

        /* ---------- ① 小信号直通：1× / 窄带关 ---------- */
        AudioChain c1 = new AudioChain();
        c1.setVolume(100);
        c1.setGain(1);
        c1.setPresence(false);
        c1.snap();
        double inPeak = 0.1;                       // -20 dBFS
        double outPeak = 0;
        int phase = 0;
        for (int f = 0; f < 20; f++) {             // 400 ms，让滤波器进入稳态
            short[] fr = sine(1000, inPeak, FRAME, phase);
            phase += FRAME;
            c1.process(fr, FRAME);
            if (f >= 10) outPeak = Math.max(outPeak, peak(fr));
        }
        ok("-20 dBFS 的 1 kHz 在 1× 下基本原样（峰 " + fmt(outPeak) + "）",
                outPeak > inPeak * 0.8 && outPeak < inPeak * 1.15, outPeak);

        /* ---------- ② 极端增益不硬削波 ---------- */
        AudioChain c2 = new AudioChain();
        c2.setVolume(150);
        c2.setGain(3);
        c2.setPresence(true);
        c2.snap();
        double worst = 0;
        phase = 0;
        for (int f = 0; f < 40; f++) {
            /* 满刻度方波：比任何真实语音都更狠（μ-law 解码出来的峰值贴着 ±0.98） */
            short[] fr = new short[FRAME];
            for (int i = 0; i < FRAME; i++) {
                fr[i] = (short) ((((i + phase) / 8) % 2 == 0) ? 32767 : -32768);
            }
            phase += FRAME;
            c2.process(fr, FRAME);
            if (f >= 20) worst = Math.max(worst, peak(fr));
        }
        ok("音量 150% × 3× × 窄带开，出口峰值仍 ≤ 0.98（实际 " + fmt(worst) + "）",
                worst <= 0.98 + 1e-4, worst);
        ok("极端增益下确实比输入更响（没有整体被压扁）", worst > 0.9, worst);

        /* ---------- ③ 窄带增强真的抬了 2.6 kHz ---------- */
        double off = gainAt(2600, false);
        double on = gainAt(2600, true);
        double ratio = on / Math.max(off, 1e-9);
        ok("2.6 kHz 处 presence 开/关的幅度比约 1.33（实际 " + fmt(ratio) + "）",
                ratio > 1.20 && ratio < 1.50, ratio);

        /* 500 Hz 不应该被明显改变 —— 只抬 2.6k，不是整体加响 */
        double r500 = gainAt(500, true) / Math.max(gainAt(500, false), 1e-9);
        ok("500 Hz 几乎不受影响（比 " + fmt(r500) + "）", r500 > 0.9 && r500 < 1.1, r500);

        /* ---------- ④ 高通：直流与极低频被压掉 ---------- */
        AudioChain c4 = new AudioChain();
        c4.setVolume(100);
        c4.setGain(1);
        c4.setPresence(false);
        c4.snap();
        double dcOut = 0;
        for (int f = 0; f < 20; f++) {
            short[] fr = new short[FRAME];
            for (int i = 0; i < FRAME; i++) fr[i] = 16000;     // 恒定 0.49
            c4.process(fr, FRAME);
            if (f == 19) dcOut = Math.abs(fr[FRAME - 1]) / 32767.0;
        }
        ok("直流被高通压到接近 0（实际 " + fmt(dcOut) + "）", dcOut < 0.02, dcOut);

        /* ---------- ⑤ 音量 0 应该真的没声 ---------- */
        AudioChain c5 = new AudioChain();
        c5.setVolume(0);
        c5.setGain(1);
        c5.setPresence(false);
        c5.snap();
        double v0 = 0;
        phase = 0;
        for (int f = 0; f < 10; f++) {
            short[] fr = sine(1000, 0.5, FRAME, phase);
            phase += FRAME;
            c5.process(fr, FRAME);
            if (f >= 5) v0 = Math.max(v0, peak(fr));
        }
        ok("音量 0% 时输出为 0（实际 " + fmt(v0) + "）", v0 < 1e-4, v0);

        /* ---------- ⑥ 静音后重开不残留 ---------- */
        AudioChain c6 = new AudioChain();
        c6.setVolume(100);
        c6.setGain(1);
        c6.setPresence(false);
        c6.snap();
        for (int f = 0; f < 10; f++) {
            short[] fr = sine(200, 0.8, FRAME, f * FRAME);
            c6.process(fr, FRAME);
        }
        c6.reset();                                 // 相当于 flush()
        short[] fr = sine(1000, 0.2, FRAME, 0);
        c6.process(fr, FRAME);
        ok("reset 之后第一帧不带上一次的尾巴", peak(fr) < 0.3, peak(fr));

        System.out.println("\n================  " + pass + " \u901a\u8fc7 / " + fail + " \u5931\u8d25  ================");
        if (fail > 0) System.exit(1);
    }

    /** 测某个频率的稳态增益（presence 开 / 关）。 */
    private static double gainAt(double freq, boolean presence) {
        AudioChain c = new AudioChain();
        c.setVolume(100);
        c.setGain(1);
        c.setPresence(presence);
        c.snap();
        double amp = 0.2;
        double out = 0;
        int phase = 0;
        /* presence 的增益是按帧爬的（0.5 dB/帧），2.5 dB 要 5 帧才到位，
         * 所以先跑 20 帧再测。 */
        for (int f = 0; f < 40; f++) {
            short[] fr = sine(freq, amp, FRAME, phase);
            phase += FRAME;
            c.process(fr, FRAME);
            if (f >= 30) out = Math.max(out, rms(fr, 0));
        }
        return out / (amp / Math.sqrt(2));
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.4f", v);
    }
}
