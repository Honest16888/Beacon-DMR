# DeepCW 模型 × onnxruntime-web（最小 PoC）

**只回答一个问题**：这台机器的浏览器（Chrome / WebView 同源）里，onnxruntime-web 的
wasm 后端能不能跑出与 Python onnxruntime **一致**的 logits。

不碰 App 主链路 —— 不加载 `web/index.html`，不接界面，不接音频。

## 输入从哪来

用 `tests/deepcw/` 已经验收过的**金标准频谱**（与官方 Python 实现差 3.4e-7）：

```
tests/deepcw/spec_ref.py          合成的 20 WPM / 700 Hz CW → spec.f32（753 帧 × 65 bin）
tests/deepcw/poc/make_ref.py      Python onnxruntime 跑同一份 spec.f32 → ref_logits.f32
tests/deepcw/poc/index.html       wasm 跑同一份 spec.f32 → 与 ref_logits.f32 逐值比
```

所以这一层**不是**在验频谱前端（那层已经钉死），而是把「频谱 → 模型」这一段单独拿出来，
换运行时重算一遍。频谱这一侧喂的是同一串比特，两侧唯一的差别就是推理实现。

## 跑

```bash
# 1. 模型（15 MB，不进 git，按 sha256 钉版本）
python tools/fetch_deepcw.py

# 2. 金标准频谱 + Python 参考 logits
python tests/deepcw/spec_ref.py --out tests/out/deepcw
python tests/deepcw/poc/make_ref.py        # Python onnxruntime 1.20.x

# 3. 装 wasm 运行时（node_modules 已 gitignore）
cd tests/deepcw/poc && npm install && cd -

# 4. 无头 Chrome 跑一遍（自带静态服务，不需要另起 python -m http.server）
CHROME_PATH=/path/to/chrome node tests/deepcw/poc/run_poc.js
```

`run_poc.js` 起一个只读静态服务（带 COOP/COEP 头），开无头 Chrome，抓页面里的
`window.__poc`。退出码 0 = PASS。也可以直接浏览器打开那个页面看表格，但要**经 http**，
`file://` 是不透明源，wasm 与 `fetch` 都会被挡。

## 结果（2026-11-04，本机 Chrome 154 headless，单线程 wasm）

| 项 | 值 |
|---|---|
| 输入 | `[1, 1, 753, 65]`，11.28 s @3.2 kHz |
| 输出 | `[1, 377, 42]`（T = 753/2，与 `output_time_stride: 2` 对得上） |
| 加载的 wasm | `ort-wasm-simd-threaded.wasm` —— **SIMD 路径生效** |
| 推理耗时 | 185 ms（建 session 486 ms，含 15 MB 模型解析） |
| 实时倍率 | **≈ 60× 实时** |
| 逐帧 argmax 一致率 | 100%（377/377） |
| 解码文本 | `CQ CQ DE BH6RGQ K` —— 与 Python 完全一致 |
| 余弦相似度 | 0.9999999958 |
| max\|Δlogits\| | 5.8e-2（出现在 p≈1e-14 的格子上，见下） |
| max\|Δp\|（逐帧 top-1） | 1.4e-3（最差一帧 p = 0.9743 vs 0.9758） |
| \|Δp\| 分位（逐帧 top-1） | p50 0 · p90 0 · p99 7.8e-4 |
| 重复运行 | 逐位相同（多次跑 `maxD` 完全一致） |

**结论：能跑，而且对得上。** 60× 实时意味着 PC 端的推理不再是瓶颈；
手机 WebView 按经验打折（1/5～1/10）也还在实时之上。

## 两个坑（都会让人得出错误结论）

### 1. `env.wasm.wasmPaths` 必须是**绝对 URL**

传 `'node_modules/onnxruntime-web/dist/'` 会报

```
no available backend found. ERR: [wasm] TypeError:
Failed to resolve module specifier 'node_modules/onnxruntime-web/dist/ort-wasm-simd-threaded.mjs'
```

读起来像「文件没找到」，实际是 ort 1.20 用 `dynamic import()` 去拉那个 `.mjs`，
相对路径会被当成 **bare specifier**（等于 `import 'react'`），浏览器解析不了。
用 `new URL('node_modules/onnxruntime-web/dist/', document.baseURI).href`。

### 2. 容差不能放在 log_probs 的绝对值上

输出是 log-softmax，最小值能到 **−31.7**（p≈3e-14）。那一档的 0.058 绝对差在概率上
是 1e-15 量级 —— 纯 f32 舍入，不携带信息。拿 `max|Δlogits| < 1e-3` 当判据会**必然失败**，
而失败的原因和模型对不对毫无关系。

正确的三条判据（现在的 `ok` 就是这三条 + 形状）：

1. **逐帧 argmax 全等** —— 结构一致性，错一个字符这里就会掉；
2. **解码文本相同** —— 端到端；
3. **逐帧 top-1 概率差 < 5e-3** —— 数值一致性，真正该用绝对阈值的地方。

## 这一版**没有**做的

- **8 秒窗 / 2 秒步进 / 后验加权拼接**：那是解码器的窗策略，PoC 一次喂完 11 秒，
  只为对齐数值。真接进 App 要照官方 `decode_morse.py` 的窗口逻辑做。
- **实时环形缓冲**：现在是「一段音频 → 一次推理」。实时要按 8 秒窗攒帧。
- **手机端实测**：本机只有桌面 Chrome。WebView 的 wasm 版本一致（同 Chromium），
  但内存（15 MB 模型 + wasm 堆）和首次加载时间要上真机才知道。
- **模型进包**：`model.onnx` 15 MB，装进 APK 会让包从 2.2 MB 变成 17 MB。
  真接的时候大概要改成「首次使用时下载 + 缓存」，或者用 `model_narrow.onnx`（1.1 MB）。
