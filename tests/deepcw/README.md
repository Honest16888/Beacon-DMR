# DeepCW 输入频谱（模型的前端）

[DeepCW](https://github.com/e04/deepcw-engine) 的解码模型只认一种输入：**3.2 kHz、
65 个频点、每 15 ms 一帧的归一化幅度谱**。这个目录只做这一件事——把那口"饲料"
做得和训练时一致。

先把这一步做对，再接模型：频谱偏半个窗口或错半个 bin，模型不会报错，
只会**很有信心地输出错字**。

## 链路

```
任意采样率 PCM
  → 255 抽头 Blackman 窗 sinc 重采样到 3200 Hz（截止 1.4 kHz）
  → reflect 填充 128 采样
  → 周期 Hann(256)  ·  hop 48（15 ms）
  → rFFT 取 32…96 号 bin = 400–1200 Hz（12.5 Hz 一格，共 65 格）
  → log1p(幅度 / 噪声底)，噪声底 = 90 分位 / 2.146
→ 模型输入 [1, 1, frames, 65]
```

常数全部来自 `model.onnx.json`，不是选的：

| 量 | 值 |
|---|---|
| 采样率 | 3200 Hz |
| FFT / 窗 | 256 / 周期 Hann（`hanning(257)[:-1]`，**不是** `hanning(256)`） |
| hop | 48（15 ms，66.7 帧/秒） |
| 频段 | 400–1200 Hz，65 bin |
| 归一化 | `noise_q90_log1p`（90 分位 / √(−2ln0.1)） |
| 模型输入名 / 布局 | `spectrogram` / `[batch, channel, time, frequency]` |

**两个坑**（都会静默出错）：

1. **padding 是 numpy 的 `reflect`，不重复边沿**。`[1,2,3]` 补 2 得到
   `[3,2,1,2,3,2,1]`。写成"照抄边沿"（symmetric）每帧偏半个窗，每句开头丢一个字母。
2. **重采样不能只用线性插值**。官方 Python 例子为了少依赖用了线性插值，
   但训练音频是 ffmpeg 重采样过的。48 kHz 下 1600 Hz 以上的能量会折回模型看的
   400–1200 Hz；这里用带抗混叠的窗 sinc。

## 文件

| 文件 | 作用 |
|---|---|
| `dsp.js` | 前端实现。浏览器挂 `window.DeepCW`，Node 走 `module.exports`——一份代码两处用 |
| `spec_ref.py` | **金标准**：逐行照抄官方 `decode_morse.py` 的 `audio_to_spectrogram()` / `normalize_spectrogram()`，外加合成 CW |
| `verify.js` | 把 `dsp.js` 的结果和 `spec_ref.py` 的裸 f32 逐值比 |
| `render_png.py` | 直接把 `spec.f32` 画成瀑布图（不经浏览器，图 = 金标准） |
| `index.html` | 可交互的演示页：改 WPM / 音调 / 信噪比，或载入自己的 WAV |
| `sim_spec.py` | **模拟件**：不经过音频，直接在 65 bin × 15 ms 网格上合成频谱（可出图、可喂模型） |
| `check_sim.py` | 模拟件 vs 真链路的结构对照（逐帧峰值相关 ≥ 0.97 才算过） |

## 跑

```bash
# 1. 生成金标准（合成 20 WPM / 700 Hz / 0 dB 的 CW）
python tests/deepcw/spec_ref.py --out tests/out/deepcw

# 2. 逐值核对
node tests/deepcw/verify.js --dir tests/out/deepcw

# 3. 出图
python tests/deepcw/render_png.py --dir tests/out/deepcw
```

`render_png.py` 和 `spec_ref.py` 需要 numpy；`render_png.py` 另外要 Pillow。
`dsp.js` 无依赖。

当前结果（2026-11-03）：

```
PASS  重采样 48k→3.2k     max|Δ|=0.00e+0
PASS  频谱（归一化）       max|Δ|=3.43e-7   容差 2e-4
753 帧 × 65 bin，66.8 帧/秒，700 Hz 每帧最强（有声时）
```

## 还没做

- **模型推理**：`model.onnx`（约 14 MB）与运行时还没接。先在 WebView 里跑
  onnxruntime-web，还是走原生 ONNX Runtime，等频谱这层验收后再定。
- **CTC / 重叠拼接**：官方参考实现里 8 秒窗、2 秒步进、后验加权拼接那一套，
  是解码器的事，不在这层。
- **实时输入**：目前是「一段音频 → 一张图」。接麦克风要另做环形缓冲。

## 模拟件（`sim_spec.py`）

不经过音频，直接在 65 bin × 15 ms 的网格上把图算出来，用来单独调「SNR / 漂移 /
衰落 / 两个信号同时在场」这类参数 —— 走真链路的话每改一个都得再过一遍重采样。

```bash
python tests/deepcw/sim_spec.py --decode          # 合成 → 落盘 → 顺便让模型读一遍
python tests/deepcw/check_sim.py                  # 和真链路的结构对照（PASS/FAIL）
```

**判据不看图，看两件可核对的事**：

- `sim_spec.py --decode` 解出来必须等于输入的报文（当前 `CQ CQ DE BH6RGQ K` 一致）；
- `check_sim.py`：逐帧峰值包络相关 **0.985**、有声帧的最强 bin 一致率 **99.5%**
  （都在 700 Hz）。

两个写错就会「图好看、模型读不出」的地方，注释里都标了：

1. 一帧的窗是 **256 个采样（80 ms）**，不是 256 个毫秒槽。写成 256 ms 的窗，
   60 ms 的点划会被抹成一根连续的线（第一版就是这样，解码出来 `MMN0`）。
2. 窗内是 **Hann 加权线性均值**，不是平方平均。RMS 会把 60 ms 的空档抬到 0.6，
   符号之间的静默消失，模型看到的是不停的载波。

另外 `keying_envelope` **只铺完整句子**（末尾留静音），差半个字符就截断的话，
模型输出会少最后一个字；但如果窗口短到一句都装不下，它会退而铺半句 ——
否则整张图是静音，相关系数会掉到 0 附近，看着像「模拟件完全不对」。
