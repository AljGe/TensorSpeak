# fastt — on-device TTS from Inflect-Micro-v2-ONNX

An Android text-to-speech engine built on [owensong/Inflect-Micro-v2-ONNX](https://huggingface.co/owensong/Inflect-Micro-v2-ONNX):
a 9.36M-parameter VITS-family model, 24 kHz mono, Apache-2.0.

The project is deliberately split in two, because the ONNX tensor contract has to be pinned
down before any Kotlin is worth writing:

| Stage | Where | Status |
| --- | --- | --- |
| 1. Python sandbox — prove the pipeline, document the contract | `src/`, `scripts/` | done, bit-exact vs upstream |
| 2. Android skeleton — same pipeline on ONNX Runtime Android | `android/` | builds; unit tests green |
| 3. eSpeak-ng via NDK/JNI + `TextToSpeechService` | `android/app/src/main/cpp/` | builds; on-device parity test not yet executed |

## Setup

Requires [Nix](https://nixos.org/download.html) with flakes and [direnv](https://direnv.net).

```bash
git submodule update --init --recursive   # vendored eSpeak-ng 1.52.0
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
python scripts/export_android_assets.py --espeak-data   # graphs + symbols.json + voice data
python scripts/export_frontend_golden.py                # the 149-row parity corpus
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install app/build/outputs/apk/debug/app-debug.apk    # needs a device/emulator
```

The debug APK is ~78 MB: 38 MB of uncompressed ONNX graphs, ONNX Runtime's native libraries,
~0.9 MB of eSpeak-ng voice data and ~1 MB of `libinflect_espeak.so` per ABI. `abiFilters` is
limited to `arm64-v8a` and `x86_64`.

`buildToolsVersion` and `ndkVersion` are pinned in `app/build.gradle.kts` because the nix
SDK is read-only — without the pins AGP tries to auto-install its own and fails.

`OnnxTts.kt` implements the contract above. `PhonemeTokenizer` is pinned to the Python
frontend by `PhonemeTokenizerTest`, which compares against golden token arrays exported from
the sandbox.

Note that Android's `java.util.Random` is not NumPy's PRNG, so a given seed produces different
`zp_noise` on the two platforms. Audio is perceptually equivalent but not sample-identical
across Python and Android — only the graphs are shared. Parity is therefore asserted on
**phonemes and token ids**, never on waveforms.

## Stage 3 — on-device frontend and system voice

`EspeakPhonemeSource` replaces the fixture stub, so the app speaks arbitrary text, and
`InflectTtsService` exposes the engine to every app on the device (Settings → Accessibility →
Text-to-speech → Inflect).

Matching the sandbox takes three layers, not just "call eSpeak" — the Python path runs a
regex normalizer *and* phonemizer's own punctuation handling around the engine:

| Layer | Python | Kotlin |
| --- | --- | --- |
| Text normalization (money, dates, times, ordinals, `num2words`) | `inflect_nano_v2_frontend.normalize_text` | `TextNormalizer.kt` + `NumToWords.kt` |
| Punctuation preserve/restore, per-word postprocess | `phonemizer` 3.x | `PhonemizerCompat.kt` |
| Text → IPA | espeak-ng 1.52.0 via `espeakng-loader` | vendored espeak-ng 1.52.0 via JNI |

eSpeak-ng is a **git submodule** at `android/app/src/main/cpp/espeak-ng`, pinned to `1.52.0`
— the same version `espeakng-loader` ships, which is what the sandbox actually phonemizes
with. `cpp/CMakeLists.txt` deliberately does not use the upstream CMake (it fetches libsonic
over the network and builds the binary, dictionaries, docs and tests); it compiles
`libespeak-ng` and `ucd-tools` only, with async/klatt/speechPlayer/mbrola/sonic/pcaudio off.
No C++ is involved, hence `-DANDROID_STL=none`.

The voice data is trimmed from 19 MB to ~0.9 MB by
`export_android_assets.py --espeak-data` (en-us needs `phontab`, `phondata`, `en_dict`,
`lang/gmw/en*` and little else) and copied out of assets into `filesDir` on first use,
because espeak-ng opens its dictionaries with `fopen`.

### Verifying

```bash
python scripts/export_frontend_golden.py     # regenerate the corpus from the sandbox
python scripts/check_frontend_compat.py      # host check, no device needed
cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

| Check | Covers | Runs where |
| --- | --- | --- |
| `TextNormalizerTest` | the normalizer + `num2words` port, all 149 rows | JVM ✅ |
| `check_frontend_compat.py` | the punctuation/postprocess algorithm and the trimmed voice data, all 149 rows | host ✅ |
| `EspeakParityTest` | the Kotlin transliteration and the JNI marshalling end to end | device ⚠️ |

`check_frontend_compat.py` transliterates `PhonemizerCompat.kt` back into Python and drives
it through the raw `espeak_TextToPhonemes` C API against the trimmed asset data — the exact
call the JNI shim makes — so most of the device test's coverage is available on the host. It
currently reports **149/149 rows match**.

**`EspeakParityTest` has not been executed.** It compiles
(`:app:assembleDebugAndroidTest` succeeds) but `devenv.nix` sets `emulator.enable = false` /
`systemImages.enable = false` and no device is attached, so nothing has run
`./gradlew :app:connectedDebugAndroidTest`. Until it does, the Kotlin-specific half of the
port — Java vs Python regex semantics in particular — is argued for, not demonstrated.
