package net.beacon.dmr.audio;

/**
 * 接收音频的处理链，逐级对齐 WebView 版（v2.5.2 定稿的那一条）。
 *
 * <pre>
 *   μ-law 解码 → 软限幅(0.96) → 音量×增强 → 高通 180Hz → presence(2.6kHz +2.5dB)
 *              → 1/3 预增益 → 软削波(0.7 起饱和到 0.98) → AudioTrack
 * </pre>
 *
 * <p><b>为什么软限幅在前、软削波在后</b>：μ-law 解出来的峰值本来就贴着满刻度，
 * 前面那道 0.96 的软限幅几乎削不到东西；真正保证出口不硬削波的是末端那一段。
 * 顺序不能换 —— 换掉之后「录音干净、播放却噼啪」的老问题会原样回来。
 *
 * <p><b>末端那段的原理</b>：WaveShaper 的曲线定义域只有 [-1,1]，而
 * 音量×增强最高能到 4.5×，直接进曲线会在定义域外被截断。所以先用 1/3
 * 预增益压进来，曲线内部再乘回 3 —— 一进一出正好抵消，同时 0.7 以上的部分
 * 走 tanh 平滑饱和、渐近到 0.98。结果是：正常语音<b>一个样本都不动</b>，
 * 过载部分被压成软饱和而不是方波。
 *
 * <p><b>不能用「压缩器」代替</b>：DynamicsCompressor 起始时间 2 ms，
 * 抓不住单样本瞬态，实测出口仍有 1.05~1.21 的峰。
 *
 * <p>线程：{@link #process} 在音频喂数线程上调用；{@link #setVolume} 等
 * 可以在主线程调（参数是 volatile，增益按样本线性斜坡过去，不会「啪」一声）。
 */
public final class AudioChain {

    private static final float FS = 8000f;

    /** 第一道软限幅的拐点。留 4% 余量兜突发过载。 */
    private static final float LIMIT = 0.96f;
    /** 末端软削波的直通区上限与天花板。 */
    private static final float KNEE = 0.7f;
    private static final float CEIL = 0.98f;

    /** 增益每样本最大变化量。1.5 的满量程约 300 个样本（37 ms）走完。 */
    private static final float RAMP = 0.005f;

    private volatile float tgtVolume = 1f;      // 0 ~ 1.5
    private volatile float tgtGain = 1f;        // 1 / 2 / 3
    private volatile boolean presence = false;

    private float curVolume = 1f;
    private float curGain = 1f;

    /* 高通 180Hz Q0.7 */
    private float hpB0, hpB1, hpB2, hpA1, hpA2;
    /* 峰值 2600Hz Q0.9，增益 0 ~ 2.5 dB 之间爬 */
    private float pkB0, pkB1, pkB2, pkA1, pkA2;
    private float pkDb, pkDbTarget;

    /* 直列二阶节的延迟单元（Direct Form I） */
    private float hx1, hx2, hy1, hy2;
    private float px1, px2, py1, py2;

    public AudioChain() {
        hpCoeffs();
        pkDb = pkDbTarget = 0;
        pkCoeffs(0);
    }

    /* ==================== 参数 ==================== */

    /** 音量 0~150（100 = 原始电平）。 */
    public void setVolume(int pct) {
        tgtVolume = Math.max(0, Math.min(pct, 150)) / 100f;
    }

    /** 音量增强 1 / 2 / 3。 */
    public void setGain(int g) {
        tgtGain = (g < 1) ? 1 : Math.min(g, 3);
    }

    /** 窄带增强：2.6 kHz 抬 2.5 dB。 */
    public void setPresence(boolean on) {
        presence = on;
        pkDbTarget = on ? 2.5f : 0f;
    }

    public boolean presence() { return presence; }

    /** 清掉滤波器状态。换流 / 从静音恢复时调，免得把上一次的尾巴拖过来。 */
    public void reset() {
        hx1 = hx2 = hy1 = hy2 = 0;
        px1 = px2 = py1 = py2 = 0;
        curVolume = tgtVolume;
        curGain = tgtGain;
        pkDb = pkDbTarget;
        pkCoeffs(pkDb);
    }

    /** 让参数立刻到位，不做斜坡（第一帧用，省掉 40 ms 的渐入）。 */
    public void snap() { reset(); }

    /* ==================== 处理 ==================== */

    /**
     * 原地处理一段 16 bit PCM。
     *
     * @param pcm 样本
     * @param n   有效样本数
     */
    public void process(short[] pcm, int n) {
        /* 峰值增益（dB）按帧爬，一帧 20 ms 走 0.5 dB —— 2.5 dB 大约 100 ms 到位，
         * 听不出台阶，也不用每个样本重算 sin/cos。 */
        if (pkDb != pkDbTarget) {
            float d = pkDbTarget - pkDb;
            pkDb += Math.max(-0.5f, Math.min(0.5f, d));
            pkCoeffs(pkDb);
        }

        for (int i = 0; i < n; i++) {
            /* 音量与增强按样本斜坡过去。滑块一动就跳变会「啪」一声，
             * 正好是我们在查的那种声音，别自己造一个。 */
            curVolume = approach(curVolume, tgtVolume, RAMP);
            curGain = approach(curGain, tgtGain, RAMP);
            float g = curVolume * curGain;

            float x = pcm[i] / 32768f;

            /* ① 软限幅：只防真正的过载 */
            if (x > LIMIT) x = LIMIT + 0.04f * (1f - (float) Math.exp(-(x - LIMIT) / 0.04f));
            else if (x < -LIMIT) x = -LIMIT - 0.04f * (1f - (float) Math.exp((-x - LIMIT) / 0.04f));

            /* ② 音量 × 增强 */
            x *= g;

            /* ③ 高通 180 Hz */
            float hy = hpB0 * x + hpB1 * hx1 + hpB2 * hx2 - hpA1 * hy1 - hpA2 * hy2;
            hx2 = hx1; hx1 = x; hy2 = hy1; hy1 = hy;
            x = hy;

            /* ④ 峰值 2.6 kHz */
            float py = pkB0 * x + pkB1 * px1 + pkB2 * px2 - pkA1 * py1 - pkA2 * py2;
            px2 = px1; px1 = x; py2 = py1; py1 = py;
            x = py;

            /* ⑤ 末端软削波。
             *
             * WebView 版这里是 pre(1/3) → WaveShaper(曲线内部 x*=3) 两级，
             * 一进一出正好抵消 —— 净效果就是「不做任何缩放，只把 0.7 以上的
             * 部分压成 tanh 饱和」。这里直接合成一步，省掉一次乘法，
             * 也省掉「为什么先除 3 又乘 3」这种看不懂的代码。
             * 曲线的直通区是 0.7，所以正常语音一个样本都不动。 */
            float a = x < 0 ? -x : x;
            float y;
            if (a <= KNEE) y = x;
            else {
                float sat = KNEE + (CEIL - KNEE) * (float) Math.tanh((a - KNEE) / (CEIL - KNEE));
                y = x < 0 ? -sat : sat;
            }

            /* ⑥ 回 16 bit。理论上 |y| ≤ 0.98，这里再夹一次纯属保险。 */
            if (y > 1f) y = 1f; else if (y < -1f) y = -1f;
            pcm[i] = (short) Math.round(y * 32767f);
        }
    }

    private static float approach(float cur, float tgt, float step) {
        if (cur < tgt) return Math.min(tgt, cur + step);
        if (cur > tgt) return Math.max(tgt, cur - step);
        return tgt;
    }

    /* ==================== 系数 ==================== */

    private void hpCoeffs() {
        double w0 = 2 * Math.PI * 180 / FS;
        double cos = Math.cos(w0), sin = Math.sin(w0);
        double q = 0.7;
        double alpha = sin / (2 * q);
        double a0 = 1 + alpha;
        hpB0 = (float) (((1 + cos) / 2) / a0);
        hpB1 = (float) (-(1 + cos) / a0);
        hpB2 = hpB0;
        hpA1 = (float) (-2 * cos / a0);
        hpA2 = (float) ((1 - alpha) / a0);
    }

    private void pkCoeffs(float db) {
        double w0 = 2 * Math.PI * 2600 / FS;
        double cos = Math.cos(w0), sin = Math.sin(w0);
        double q = 0.9;
        double alpha = sin / (2 * q);
        double A = Math.pow(10, db / 40.0);
        double a0 = 1 + alpha / A;
        pkB0 = (float) ((1 + alpha * A) / a0);
        pkB1 = (float) (-2 * cos / a0);
        pkB2 = (float) ((1 - alpha * A) / a0);
        pkA1 = (float) (-2 * cos / a0);
        pkA2 = (float) ((1 - alpha / A) / a0);
    }
}
