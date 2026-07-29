# Third-party notices

This file lists third-party software and models redistributed with or linked into
**TensorSpeak** (the Android APK and related tooling). The application itself is licensed
under the GNU General Public License v3.0 or later - see [LICENSE](LICENSE).

---

## Inflect-Micro-v2-ONNX / Inflect-Nano-v2-ONNX

- **License:** Apache License 2.0
- **Credit:** **Inflect** models by [Owen Song](https://github.com/owenawsong)
  ([@owenawsong](https://github.com/owenawsong);
  [Inflect](https://github.com/owenawsong/Inflect);
  [Hugging Face](https://huggingface.co/owensong);
  [v2 evaluation writeup](https://huggingface.co/owensong/Inflect-Micro-v2/blob/main/docs/EVALUATION.md));
  ONNX conversion by Robert Bak ([`webtts-inflect`](https://github.com/robertbak/webtts-inflect)).
- **Sources:**
  - https://huggingface.co/owensong/Inflect-Micro-v2-ONNX
  - https://huggingface.co/owensong/Inflect-Nano-v2-ONNX
- **Used as:** `duration.onnx` and `decode.onnx` downloaded as GitHub Release ZIPs
  (`TensorSpeak-model-micro.zip` / `TensorSpeak-model-nano.zip`) into
  `filesDir/models/<variant>/` via `ModelPackManager` (packed by
  `scripts/pack_model_assets.py`; graphs are gitignored and regenerated from Hugging Face).
- **Notice:** Full Apache-2.0 text ships inside each model ZIP as `LICENSE`. See also
  [docs/MODEL_ATTRIBUTION.md](docs/MODEL_ATTRIBUTION.md).

---

## ONNX Runtime Android

- **Package:** `com.microsoft.onnxruntime:onnxruntime-android:1.20.0`
- **License:** MIT
- **Copyright:** Copyright (c) Microsoft Corporation
- **Used as:** On-device inference for the Inflect duration and decode graphs
  (`OnnxTts.kt`).

```
MIT License

Copyright (c) Microsoft Corporation

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## eSpeak-ng

- **Version:** 1.52.0 (git submodule)
- **License:** GNU General Public License v3.0 or later
- **Copyright:** Copyright (C) eSpeak-ng contributors
- **Path:** `android/app/src/main/cpp/espeak-ng`
- **Used as:** Text->IPA phonemization via JNI (`libtensorspeak_espeak.so`). Linking this
  library is why the TensorSpeak application is distributed under GPL-3.0-or-later.
- **Additional notices in the submodule:** `COPYING`, `COPYING.APACHE`,
  `COPYING.BSD2`, `COPYING.UCD`, and `src/ucd-tools/COPYING` (Unicode Character
  Database tools).

---

## Other dependencies (not redistributed as source in this tree)

| Component | License (typical) | Role |
| --- | --- | --- |
| AndroidX (`core-ktx`, `appcompat`, `activity-ktx`) | Apache-2.0 | UI / activity helpers |
| Kotlin / kotlinx-coroutines | Apache-2.0 | language & async |
| Python sandbox deps (`numpy`, `phonemizer`, `espeakng-loader`, …) | various OSS | desktop reference only; not in the APK |
