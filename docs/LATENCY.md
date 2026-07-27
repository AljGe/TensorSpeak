# Android synthesis latency

Measured on-device with `SynthesisBenchmark` (logcat tag `SynthBench`). Production stays on
the ORT **CPU** execution provider until a candidate beats it on warm service-path TTFA
without audio regressions.

## What already shipped

| Lever | Status |
| --- | --- |
| Sentence / punctuation chunking + streaming to `SynthesisCallback` | production |
| `FIRST_CHUNK_LIMIT` (96) after full text normalization | production |
| Shared `EngineRepository` for activity + service | production |
| One-shot language warm-up; ORT `RunOptions.setTerminate` on stop | production |
| Fused edge-fade / clip / PCM16 with reused scratch buffer | production |
| Preview `AudioTrack` streaming in `MainActivity` | production |
| Experimental · NNAPI / XNNPACK / thread overrides | opt-in in settings UI (falls back to CPU) |
| First-chunk latency profile (64 / 96 / 280) | opt-in in settings UI |

## Benchmark entry points

```bash
cd android
./gradlew :app:installDebug :app:installDebugAndroidTest

# Providers (CPU / XNNPACK / NNAPI) + stage timings + service-path TTFA
adb shell am instrument -w \
  -e class com.github.aljge.tensorspeak.SynthesisBenchmark#benchmarkVariantsAndProviders \
  com.github.aljge.tensorspeak.test/androidx.test.runner.AndroidJUnitRunner

# Intra-op thread / spin / global-pool sweep (Nano)
adb shell am instrument -w \
  -e class com.github.aljge.tensorspeak.SynthesisBenchmark#benchmarkCpuThreading \
  com.github.aljge.tensorspeak.test/androidx.test.runner.AndroidJUnitRunner
```

Look for `stages …` lines (normalize / phoneme / duration / decode / post), `svc50` (PCM
path), `dNative`, and `thermal=`.

Optional ORT chrome-trace: construct with `RuntimeConfig(enableProfiling = true)`.

## Experimental track (not in the APK)

### Offline optimized ONNX / ORT format

```bash
python scripts/optimize_onnx_assets.py --model all
adb push out/experimental-ort/nano \
  /sdcard/Android/data/com.github.aljge.tensorspeak/files/experimental-ort/nano
```

`benchmarkVariantsAndProviders` probes `experimental-ort/<variant>/` and logs load/TTFA.
Gate: colder session construction and no worse warm decode vs online `ALL_OPT`.

### NNAPI

Enabled as `OnnxTts.Provider.NNAPI` in the provider benchmark. Expect fragmentation on 1-D
conv / `ConvTranspose`; keep only if partitions are large and TTFA wins.

### Selective INT8 decode

```bash
python scripts/quantize_decode_experiment.py --model nano
```

Writes `out/experimental-int8/<variant>/decode.int8.onnx` plus float `duration.onnx`.
Gate: device TTFA/RTF, duration stability, numerical sanity, blinded listening. Do **not**
ship FP16 on CPU (ORT upcasts). QNN is Snapdragon-only and needs a custom ORT build.

## Acceptance

Primary: lower warm p50/p95 **service-path** TTFA for Micro and Nano, especially
normalization-expanding lines, with frontend chunk/phoneme/token parity intact.

Secondary: no worse underruns, thermal throttling, peak memory, or total RTF.
