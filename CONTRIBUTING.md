# Contributing to TensorSpeak

Thanks for taking an interest. TensorSpeak is an on-device Android TTS engine with a
Python reference pipeline that the Kotlin/JNI port must match.

## Setup

Requires [Nix](https://nixos.org/download.html) (flakes) and [direnv](https://direnv.net).

```bash
git submodule update --init --recursive   # eSpeak-ng 1.52.0
direnv allow                              # Python 3.11, uv, Android SDK/NDK
python scripts/fetch_model.py             # models/micro/ and models/nano/ (not in git)
```

Python-only work (skip the multi-GB Android SDK):

```bash
INFLECT_ANDROID=0 direnv reload
```

## Before you change the frontend

The Python side under `src/inflect_sandbox/` is the **specification**. Android must
match its normalization, chunking, and token ids.

- After frontend changes: regenerate assets/goldens with
  `scripts/export_android_assets.py` and `scripts/export_frontend_golden.py` as needed.
- Parity is asserted on **phonemes and token ids only**, never waveforms (Android’s
  PRNG is not NumPy’s).
- Prefer JVM/host tests (`testDebugUnitTest`, `check_frontend_compat.py`) over device
  tests when the logic is pure string handling.

Longer architecture and release notes for agents live in [AGENTS.md](AGENTS.md).

## Pull requests

1. Keep changes focused; match existing style.
2. Run the checks that touch your change (Python parity and/or Android unit tests).
3. Do not commit models, ONNX graphs, signing material (`.jks`, `.signing.env`), or
   generated `espeak-ng-data/`.

## License

By contributing, you agree your contributions are licensed under the same terms as
the project: [GPL-3.0-or-later](LICENSE) (Copyright (C) 2026 AljGe).
