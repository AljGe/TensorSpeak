# TensorSpeak

On-device Android text-to-speech built on **[Inflect](https://github.com/owenawsong/Inflect)** by [Owen Song](https://github.com/owenawsong) ([@owenawsong](https://github.com/owenawsong)): complete local text-to-waveform stacks under ~10M parameters. This app ships the official ONNX exports [Inflect-Micro-v2-ONNX](https://huggingface.co/owensong/Inflect-Micro-v2-ONNX) (9.36M, default) and [Inflect-Nano-v2-ONNX](https://huggingface.co/owensong/Inflect-Nano-v2-ONNX) (3.96M, faster)—24 kHz mono, Apache-2.0. For what the models are, how they were measured, and runtime benchmarks, see Owen’s [Inflect v2 evaluation writeup](https://huggingface.co/owensong/Inflect-Micro-v2/blob/main/docs/EVALUATION.md) and the [Micro v2](https://huggingface.co/owensong/Inflect-Micro-v2) / [Nano v2](https://huggingface.co/owensong/Inflect-Nano-v2) model cards on Hugging Face.

TensorSpeak is a separate Android port and system TTS wrapper; the voices and graphs are Owen’s Inflect releases. Choose **Micro** or **Nano** in the app or in system TTS settings.

## Install

[![Get it on Obtainium](https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png)](http://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/AljGe/TensorSpeak)

Install and update from [GitHub Releases](https://github.com/AljGe/TensorSpeak/releases) with [Obtainium](https://github.com/ImranR98/Obtainium), or download `TensorSpeak-*-unified.apk` manually. Releases are signed, unified (`arm64-v8a` + `x86_64`), and include both model variants.

After install, enable **TensorSpeak** under **Settings → Accessibility → Text-to-speech output** (or your device’s equivalent).

**Voice settings:** Micro uses variation `0.62` and Nano `0.58` in the default “Balanced” profile; “Stable pronunciation” lowers variation for steadier phrasing.

## How synthesis works

```
text → normalize → eSpeak-ng (IPA) → token ids → duration.onnx → decode.onnx → 24 kHz audio
```

Duration expansion is inside `duration.onnx`; callers only add Gaussian `zp_noise` between the two graphs. Tensor names and shapes are documented in [docs/TENSOR_CONTRACT.md](docs/TENSOR_CONTRACT.md). Latency levers and on-device benchmarks are in [docs/LATENCY.md](docs/LATENCY.md).

The same pipeline is implemented twice: a **Python reference** (`src/inflect_sandbox/`, bit-exact vs upstream) and an **Android app** (`android/`, Kotlin + ONNX Runtime + vendored eSpeak-ng 1.52.0). Phoneme and token parity are tested against the reference; waveforms differ slightly on Android because the random number generator is not NumPy’s.

## Development setup

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, parity rules, and PR expectations.

Requires [Nix](https://nixos.org/download.html) (flakes) and [direnv](https://direnv.net).

```bash
git submodule update --init --recursive   # eSpeak-ng 1.52.0
direnv allow                              # Python 3.11, uv, Android SDK/NDK
python scripts/fetch_model.py             # models/micro/ and models/nano/ (not in git)
```

To work on the Python sandbox only (skip the multi-GB Android SDK):

```bash
INFLECT_ANDROID=0 direnv reload
```

### Python reference

```bash
python scripts/synthesize.py --text "Hello world." --output out/sample.wav
python scripts/parity_check.py              # waveform parity vs upstream (micro)
python scripts/parity_check.py --model nano
python scripts/inspect_graphs.py            # regenerate docs/TENSOR_CONTRACT.md
```

### Android

```bash
python scripts/export_android_assets.py --espeak-data
python scripts/export_frontend_golden.py
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Debug builds are large (~94 MB): uncompressed ONNX graphs, ONNX Runtime natives, trimmed eSpeak voice data (~0.9 MB), and `libtensorspeak_espeak.so` per ABI.

**Frontend parity** (normalization, phonemizer-compatible punctuation, IPA) is covered on the JVM and on the host where possible:

| Check | Where |
| --- | --- |
| `TextNormalizerTest`, `TextChunkerTest`, `PhonemeTokenizerTest` | JVM (`testDebugUnitTest`) |
| `check_frontend_compat.py` | Host (149-row golden corpus) |
| `EspeakParityTest` | Device (`connectedDebugAndroidTest`) |

Agent-oriented architecture notes, release signing, and Obtainium checklist live in [AGENTS.md](AGENTS.md).

## Release APK

Models and ONNX assets are gitignored; fetch and export before a release build:

```bash
python scripts/fetch_model.py
python scripts/export_android_assets.py --espeak-data
```

Configure signing via [`.signing.env.example`](.signing.env.example) → `.signing.env` (or `android/keystore.properties`). Keep the keystore **outside** the repository.

```bash
set -a && source .signing.env && set +a
./scripts/build_signed_release_apk.sh          # → TensorSpeak-<version>-unified.apk
./scripts/build_signed_release_apk.sh --upload # refresh GitHub release asset
```

Publish releases (not draft-only) with tag matching `versionName` in `android/app/build.gradle.kts`, one APK asset per release, and links to [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [docs/MODEL_ATTRIBUTION.md](docs/MODEL_ATTRIBUTION.md).

## Credits

| | |
| --- | --- |
| **Inflect** (models, training, Hugging Face releases) | [Owen Song](https://github.com/owenawsong) ([@owenawsong](https://github.com/owenawsong)) — [Inflect](https://github.com/owenawsong/Inflect), [evaluation & benchmarks](https://huggingface.co/owensong/Inflect-Micro-v2/blob/main/docs/EVALUATION.md) |
| **ONNX packaging** | Robert Bak ([`webtts-inflect`](https://github.com/robertbak/webtts-inflect)) — graphs used in this APK |
| **TensorSpeak** (Android app, this repo) | [AljGe](https://github.com/AljGe) |

## License

TensorSpeak is [GPL-3.0-or-later](LICENSE) (Copyright (C) 2026 AljGe). Inflect ONNX weights are Apache-2.0; eSpeak-ng is GPL-3.0. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [docs/MODEL_ATTRIBUTION.md](docs/MODEL_ATTRIBUTION.md); the in-app open-source screen mirrors those notices offline.
