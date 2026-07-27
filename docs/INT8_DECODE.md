# INT8 decode research notes

Status: **experimental only — do not ship in the APK yet.**  
Date: 2026-07-27. Device: Pixel 9a (`tegu`), ORT CPU, 4 threads.  
Artifacts: `out/experimental-int8/` (gitignored). Tooling: [`scripts/quantize_decode_experiment.py`](../scripts/quantize_decode_experiment.py).

## Policy

| Graph | Quantized? | Why |
| --- | --- | --- |
| `duration.onnx` | No (FP32) | Timing errors become audible duration drift |
| `decode.onnx` | Static QDQ INT8 | Size + decode latency candidate |

I/O tensor names/dtypes are unchanged, so Kotlin needs no pipeline changes for a drop-in swap via `OnnxTts.fromAssets(..., graphDirectory=...)`.

## Gate A — size

| Variant / profile | FP32 decode | INT8 decode | Reduction |
| --- | --- | --- | --- |
| nano / default | 12.57 MB | 3.84 MB | **−69.5%** |
| nano / conv_only | 12.57 MB | 4.81 MB | **−61.8%** |
| micro / default | 30.43 MB | 8.37 MB | **−72.5%** |

**Pass.** Peak native delta while measuring experimental graphs was lower than FP32 assets (micro ~60→31 MB, nano ~28→21–27 MB).

## Gate B — latency (Pixel 9a)

Warm first-chunk / service-path TTFA from `SynthesisBenchmark#benchmarkExperimentalGraphs`.

### Default QDQ (full graph)

| Variant | Case | FP32 ttfa50 | INT8 ttfa50 | FP32 decodeMs | INT8 decodeMs |
| --- | --- | --- | --- | --- | --- |
| micro | Short | 566 | **197** | 518 | **161** |
| micro | Sentence | 1494 | **571** | 1356 | **489** |
| micro | Expanded | 2462 | **943** | 2155 | **757** |
| nano | Short | 251 | **136** | 217 | **93** |
| nano | Sentence | 768 | **341** | 671 | **270** |
| nano | Expanded | 1213 | **553** | 1052 | **394** |

**Pass** for default QDQ on both variants (≈2–3× decode speedup). Host x86_64 timing was inconclusive; ARM wins.

### Nano `conv_only` (weights/acts on Conv only)

| Case | FP32 ttfa50 | INT8 ttfa50 | FP32 decodeMs | INT8 decodeMs |
| --- | --- | --- | --- | --- |
| Short | 249 | **168** | 207 | **143** |
| Sentence | 799 | **537** | 700 | 763* |
| Expanded | 1235 | **820** | 1063 | **628** |

\*One first-sample stage line showed a noisy decode reading; p50 totals still win. **Pass** overall, smaller win than full QDQ.

## Gate C — quality

Waveform correlation vs FP32 decode on identical duration latents / `zp_noise` (host):

| Profile | Mean corr | Mean MAE | Spectrogram / listen notes |
| --- | --- | --- | --- |
| nano default | **0.33** | 0.035 | Speech-shaped but formants/timing drift; **fail** for ship |
| nano per_tensor | 0.31 | 0.035 | Same class as default |
| nano uint8_act | 0.34 | 0.035 | Same |
| nano reduce_range | 0.35 | 0.035 | Same |
| nano **conv_only** | **0.78** | 0.018 | Formants track FP32; residual hiss; **conditional pass** |
| micro default | **0.75** | 0.019 | Same class as nano conv_only; **conditional pass** |

Side-by-side WAVs: `out/experimental-int8/<variant>/listen/{NN}-{fp32,int8}.wav`  
Spectrograms: `.../listen/spec/` (SoX).

**Blinded listening verdict:** default full QDQ on Nano is not shippable on quality. Prefer **`conv_only` for Nano** and **default for Micro** pending a human ear pass on the WAV pairs (money/date/loanword lines). Do not treat sample-correlation as perceptual proof—generative decode can shift phase while still sounding OK, but corr≈0.33 is a hard reject.

## Tuning ladder results

`--profile` presets in the quantize script:

1. Richer calibration — not required to beat size/TTFA; still useful before ship.
2. `per_tensor` / `uint8_act` / `reduce_range` — no quality win over default.
3. `conv_only` (`op_types_to_quantize=['Conv']`) — best quality/size trade on Nano; still beats FP32 TTFA on device.
4. Dynamic quant / NNAPI-on-INT8 / QNN — not attempted (low priority / platform-specific).

## Operational notes (WSL)

- Use **Windows `adb.exe`** when the phone is attached on the Windows USB stack.
- After `adb push`, run `chmod -R a+rx …/experimental-ort` so the app uid can traverse shell-owned dirs.
- Instrumented tests need a **debug-signed** app; uninstall a release build first (reinstall release afterward).

## Decision

| Question | Answer |
| --- | --- |
| Ship INT8 decode in production APK now? | **No** |
| Keep experimenting? | **Yes** |
| Best Nano candidate | `out/experimental-int8/nano-conv_only/` |
| Best Micro candidate | `out/experimental-int8/micro/` (default QDQ) |
| Why not ship | Need human listening sign-off; export pipeline still copies FP32 from `models/`; dual-variant APK policy unchanged |
| If shipping later | Add quant step to `export_android_assets.py`, update attribution, keep duration FP32, prefer Nano=`conv_only` / Micro=`default`, gate on Pixel TTFA + ear |

Primary path if quality stays borderline after listening: ask upstream Inflect for QAT decode, or keep INT8 as a size-only Nano SKU behind an explicit setting—not the default.
