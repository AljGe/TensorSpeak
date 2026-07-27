# fastt — on-device TTS from Inflect-Micro-v2-ONNX

An Android text-to-speech engine built on [owensong/Inflect-Micro-v2-ONNX](https://huggingface.co/owensong/Inflect-Micro-v2-ONNX):
a 9.36M-parameter VITS-family model, 24 kHz mono, Apache-2.0.

The project is deliberately split in two, because the ONNX tensor contract has to be pinned
down before any Kotlin is worth writing:

| Stage | Where | Status |
| --- | --- | --- |
| 1. Python sandbox — prove the pipeline, document the contract | `src/`, `scripts/` | done, bit-exact vs upstream |
| 2. Android skeleton — same pipeline on ONNX Runtime Android | `android/` | builds; phoneme frontend stubbed |
| 3. eSpeak-ng via NDK/JNI + `TextToSpeechService` | — | not started |

## Setup

Requires [Nix](https://nixos.org/download.html) with flakes and [direnv](https://direnv.net).

```bash
direnv allow          # builds the devenv shell (Python 3.11 + uv, and the Android SDK/NDK)
python scripts/fetch_model.py     # downloads the graphs into models/, verifies sha256
```

The Android SDK is a multi-GB download. To skip it while working on Stage 1 only:

```bash
INFLECT_ANDROID=0 direnv reload
```

## Stage 1 — Python sandbox

```bash
python scripts/inspect_graphs.py    # regenerates docs/TENSOR_CONTRACT.md from the graphs
python scripts/synthesize.py --text "Hello world." --output out/sample.wav
python scripts/parity_check.py      # bit-parity against models/onnx/inference_onnx.py
```

`parity_check.py` is the acceptance test: it runs the upstream reference implementation and
ours over the same sentences and seeds and asserts the waveforms match. It currently reports
`max|diff| = 0` on all fixtures.

Text→IPA is delegated to the upstream frontend (`inflect_vits_frontend.run_vits_frontend`)
rather than reimplemented — it is bound to a specific eSpeak-ng build plus a hand-tuned
override table, and any drift there produces subtly wrong audio. Everything after that
boundary lives in `src/inflect_sandbox/` and is the spec the Android port follows.

### The pipeline

See [docs/TENSOR_CONTRACT.md](docs/TENSOR_CONTRACT.md), generated from the graphs themselves.

```
text -> eSpeak-ng IPA -> ids + interleaved blanks -> [1, text_len]
     -> duration.onnx -> m_p_exp, logs_p_exp, y_mask   all [1, 192, mel_len]
     -> + zp_noise ~ N(0,1)
     -> decode.onnx -> waveform [1, 1, wav_len] @ 24 kHz
```

**Duration expansion happens inside `duration.onnx`** — its outputs are already at `mel_len`.
There is no length-regulation step to implement by hand; the only thing a caller adds between
the two graphs is the `zp_noise` draw. This is the single most important fact for porting.

## Stage 2 — Android

```bash
python scripts/export_android_assets.py   # graphs + symbols.json + test fixtures
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install app/build/outputs/apk/debug/app-debug.apk    # needs a device/emulator
```

The debug APK is ~79 MB: 38 MB of uncompressed ONNX graphs plus ONNX Runtime's native
libraries. `abiFilters` is already limited to `arm64-v8a` and `x86_64`.

`buildToolsVersion` and `ndkVersion` are pinned in `app/build.gradle.kts` because the nix
SDK is read-only — without the pins AGP tries to auto-install its own and fails.

`OnnxTts.kt` implements the contract above. `PhonemeTokenizer` is pinned to the Python
frontend by `PhonemeTokenizerTest`, which compares against golden token arrays exported from
the sandbox.

Text→IPA on Android is **stubbed** (`FixturePhonemeSource`) pending the Stage 3 eSpeak-ng JNI
build; the app synthesizes fixture sentences only. The NDK and CMake are already wired into
`devenv.nix` so that work needs no toolchain changes.

Note that Android's `java.util.Random` is not NumPy's PRNG, so a given seed produces different
`zp_noise` on the two platforms. Audio is perceptually equivalent but not sample-identical
across Python and Android — only the graphs are shared.
