# TensorSpeak - on-device TTS from Inflect Micro / Nano ONNX

An Android text-to-speech engine built on
[owensong/Inflect-Micro-v2-ONNX](https://huggingface.co/owensong/Inflect-Micro-v2-ONNX)
(9.36M params) and
[owensong/Inflect-Nano-v2-ONNX](https://huggingface.co/owensong/Inflect-Nano-v2-ONNX)
(3.96M params): VITS-family models, 24 kHz mono, Apache-2.0. Both graph pairs ship in the
APK; pick Nano (smaller/faster) or Micro (default, higher quality) in the harness /
engine settings.

Quality defaults are tuned per model:

- Micro variation `0.62`, Nano variation `0.58` (`Balanced`)
- Optional `Stable pronunciation` profile applies a lower variation for steadier phrasing

The project is deliberately split in two, because the ONNX tensor contract has to be pinned
down before any Kotlin is worth writing:

| Stage | Where | Status |
| --- | --- | --- |
| 1. Python sandbox - prove the pipeline, document the contract | `src/`, `scripts/` | done, bit-exact vs upstream |
| 2. Android skeleton - same pipeline on ONNX Runtime Android | `android/` | builds; unit tests green |
| 3. eSpeak-ng via NDK/JNI + `TextToSpeechService` | `android/app/src/main/cpp/` | builds; on-device parity test not yet executed |

## Setup

Requires [Nix](https://nixos.org/download.html) with flakes and [direnv](https://direnv.net).

```bash
git submodule update --init --recursive   # vendored eSpeak-ng 1.52.0
direnv allow          # builds the devenv shell (Python 3.11 + uv, and the Android SDK/NDK)
python scripts/fetch_model.py             # downloads micro + nano into models/<variant>/, verifies sha256
# python scripts/fetch_model.py --model nano   # one variant only
```

The Android SDK is a multi-GB download. To skip it while working on Stage 1 only:

```bash
INFLECT_ANDROID=0 direnv reload
```

## Stage 1 - Python sandbox

```bash
python scripts/inspect_graphs.py    # regenerates docs/TENSOR_CONTRACT.md from both variants
python scripts/synthesize.py --text "Hello world." --output out/sample.wav
python scripts/synthesize.py --model nano --text "Hello world." --output out/nano.wav
python scripts/parity_check.py              # bit-parity vs micro upstream
python scripts/parity_check.py --model nano # bit-parity vs nano upstream
```

`parity_check.py` is the acceptance test: it runs the upstream reference implementation and
ours over the same sentences and seeds and asserts the waveforms match. It currently reports
`max|diff| = 0` on all fixtures for both variants.

Text->IPA is delegated to the upstream frontend (`inflect_vits_frontend.run_vits_frontend`)
rather than reimplemented - it is bound to a specific eSpeak-ng build plus a hand-tuned
override table, and any drift there produces subtly wrong audio. Everything after that
boundary lives in `src/inflect_sandbox/` and is the spec the Android port follows. The
frontend is shared; only the ONNX graphs differ per variant (`models/micro/`, `models/nano/`).

### The pipeline

See [docs/TENSOR_CONTRACT.md](docs/TENSOR_CONTRACT.md), generated from the graphs themselves.

```
text -> eSpeak-ng IPA -> ids + interleaved blanks -> [1, text_len]
     -> duration.onnx -> m_p_exp, logs_p_exp, y_mask   all [1, C, mel_len]
     -> + zp_noise ~ N(0,1)
     -> decode.onnx -> waveform [1, 1, wav_len] @ 24 kHz
```

`C` is `inter_channels`: **192** (Micro) or **128** (Nano).

**Duration expansion happens inside `duration.onnx`** - its outputs are already at `mel_len`.
There is no length-regulation step to implement by hand; the only thing a caller adds between
the two graphs is the `zp_noise` draw. This is the single most important fact for porting.

## Stage 2 - Android

```bash
python scripts/export_android_assets.py --espeak-data   # both variants + symbols.json + voice data
python scripts/export_frontend_golden.py                # the 149-row parity corpus
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install app/build/outputs/apk/debug/app-debug.apk    # needs a device/emulator
```

The debug APK is ~94 MB: ~54 MB of uncompressed ONNX graphs (Micro ~38 MB + Nano ~16 MB),
ONNX Runtime's native libraries, ~0.9 MB of eSpeak-ng voice data and ~1 MB of
`libtensorspeak_espeak.so` per ABI. `abiFilters` is limited to `arm64-v8a` and `x86_64`.

`buildToolsVersion` and `ndkVersion` are pinned in `app/build.gradle.kts` because the nix
SDK is read-only - without the pins AGP tries to auto-install its own and fails.

`OnnxTts.kt` implements the contract above and loads `assets/<variant>/{duration,decode}.onnx`.
The harness spinner (also the engine settings gear) persists Nano vs Micro via
`ModelPreferences`; `TensorSpeakTtsService` reloads when the preference changes.
`PhonemeTokenizer` is pinned to the Python frontend by `PhonemeTokenizerTest`, which compares
against golden token arrays exported from the sandbox.

Note that Android's `java.util.Random` is not NumPy's PRNG, so a given seed produces different
`zp_noise` on the two platforms. Audio is perceptually equivalent but not sample-identical
across Python and Android - only the graphs are shared. Parity is therefore asserted on
**phonemes and token ids**, never on waveforms.

## Stage 3 - on-device frontend and system voice

`EspeakPhonemeSource` replaces the fixture stub, so the app speaks arbitrary text, and
`TensorSpeakTtsService` exposes the engine to every app on the device (Settings -> Accessibility ->
Text-to-speech -> TensorSpeak).

Matching the sandbox takes three layers, not just "call eSpeak" - the Python path runs a
regex normalizer *and* phonemizer's own punctuation handling around the engine:

| Layer | Python | Kotlin |
| --- | --- | --- |
| Text normalization (money, dates, times, ordinals, `num2words`) | `inflect_nano_v2_frontend.normalize_text` | `TextNormalizer.kt` + `NumToWords.kt` |
| Punctuation preserve/restore, per-word postprocess | `phonemizer` 3.x | `PhonemizerCompat.kt` |
| Text -> IPA | espeak-ng 1.52.0 via `espeakng-loader` | vendored espeak-ng 1.52.0 via JNI |

eSpeak-ng is a **git submodule** at `android/app/src/main/cpp/espeak-ng`, pinned to `1.52.0`
- the same version `espeakng-loader` ships, which is what the sandbox actually phonemizes
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
| `EspeakParityTest` | the Kotlin transliteration and the JNI marshalling end to end | device warning |

`check_frontend_compat.py` transliterates `PhonemizerCompat.kt` back into Python and drives
it through the raw `espeak_TextToPhonemes` C API against the trimmed asset data - the exact
call the JNI shim makes - so most of the device test's coverage is available on the host. It
currently reports **149/149 rows match**.

**`EspeakParityTest` has not been executed.** It compiles
(`:app:assembleDebugAndroidTest` succeeds) but `devenv.nix` sets `emulator.enable = false` /
`systemImages.enable = false` and no device is attached, so nothing has run
`./gradlew :app:connectedDebugAndroidTest`. Until it does, the Kotlin-specific half of the
port - Java vs Python regex semantics in particular - is argued for, not demonstrated.

## Building a release APK

1. Fetch models and export assets (graphs are gitignored; Apache-2.0 `LICENSE` files are not):

```bash
python scripts/fetch_model.py
python scripts/export_android_assets.py --espeak-data
```

2. Optional signing - create `android/keystore.properties` (gitignored) **or** set env vars:

```properties
storeFile=/absolute/path/to/tensorspeak-release.jks
storePassword=...
keyAlias=tensorspeak
keyPassword=...
```

```bash
export TENSORSPEAK_STORE_FILE=/absolute/path/to/tensorspeak-release.jks
export TENSORSPEAK_STORE_PASSWORD=...
export TENSORSPEAK_KEY_ALIAS=tensorspeak
export TENSORSPEAK_KEY_PASSWORD=...
```

3. Build:

```bash
cd android && ./gradlew :app:assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk  (or app-release-unsigned.apk)
```

Without keystore props/env, the release build still completes but is not release-signed.

## GitHub Releases (when you publish)

- Push the full source repository (including this README, `LICENSE`, and
  `THIRD_PARTY_NOTICES.md`) before or at the same time as the binary.
- Tag the release to match `versionName` in `app/build.gradle.kts` (currently `0.1.0`).
- Attach the signed release APK to the GitHub Release.
- Link `THIRD_PARTY_NOTICES.md` / `docs/MODEL_ATTRIBUTION.md` from the release notes.

## License

**TensorSpeak** is licensed under the [GNU General Public License v3.0 or later](LICENSE)
(Copyright (C) 2026 AljGe). The project links [eSpeak-ng](https://github.com/espeak-ng/espeak-ng)
(GPL-3.0), so the application is distributed under GPL-3.0-or-later.

Third-party models and libraries (Inflect ONNX Apache-2.0, ONNX Runtime MIT, eSpeak-ng GPL)
are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Model redistribution details
are in [docs/MODEL_ATTRIBUTION.md](docs/MODEL_ATTRIBUTION.md). The in-app **Open source
licenses** screen shows the same notices offline.
