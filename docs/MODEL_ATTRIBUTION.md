# Model attribution

TensorSpeak’s speech quality comes from **[Inflect](https://github.com/owenawsong/Inflect)**, an independent
ultra-lightweight local TTS project by **[Owen Song](https://github.com/owenawsong)**
([@owenawsong](https://github.com/owenawsong); models on [Hugging Face](https://huggingface.co/owensong)).
Owen’s [Inflect v2 evaluation writeup](https://huggingface.co/owensong/Inflect-Micro-v2/blob/main/docs/EVALUATION.md)
documents benchmarks, listening tests, and runtime for Micro v2 and Nano v2; the
[Micro v2](https://huggingface.co/owensong/Inflect-Micro-v2) and
[Nano v2](https://huggingface.co/owensong/Inflect-Nano-v2) model cards summarize the release.

The Android app redistributes the **official ONNX exports** from two Hugging Face repos (not the PyTorch
checkpoints). Both are **Apache License 2.0**. Graphs are **not** bundled in the APK; they download as
GitHub Release ZIPs (`TensorSpeak-model-micro.zip` / `TensorSpeak-model-nano.zip`) that include each
variant’s `LICENSE` (and `SOURCE.json` when present) next to `duration.onnx` / `decode.onnx`.

| Variant | Hugging Face | Params | Delivery |
| --- | --- | --- | --- |
| Micro (default) | [owensong/Inflect-Micro-v2-ONNX](https://huggingface.co/owensong/Inflect-Micro-v2-ONNX) | 9.36M | `TensorSpeak-model-micro.zip` → `filesDir/models/micro/` |
| Nano | [owensong/Inflect-Nano-v2-ONNX](https://huggingface.co/owensong/Inflect-Nano-v2-ONNX) | 3.96M | `TensorSpeak-model-nano.zip` → `filesDir/models/nano/` |

**Credit:** Inflect models and reference inference by **[Owen Song](https://github.com/owenawsong)**
([@owenawsong](https://github.com/owenawsong); [Inflect](https://github.com/owenawsong/Inflect)).
Official ONNX packaging by Owen’s Hugging Face releases; conversion tooling by **Robert Bak**
([`webtts-inflect`](https://github.com/robertbak/webtts-inflect)). Each download includes `onnx/SOURCE.json`
with revision hashes and `"license": "Apache-2.0"`.

**Not committed:** The `.onnx` files, packed ZIPs under `out/model-packs/`, and trimmed
`espeak-ng-data/` are gitignored. After `python scripts/fetch_model.py`, run:

```bash
python scripts/export_android_assets.py --skip-models --espeak-data
python scripts/pack_model_assets.py
```

That writes `assets/model_manifest.json` (SHA-256 + GitHub asset names) and the release ZIPs.
For local offline debugging only, omit `--skip-models` so graphs are copied into
`android/app/src/main/assets/<variant>/` as a fallback. Release builds always skip bundling.

See also [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) and [TENSOR_CONTRACT.md](TENSOR_CONTRACT.md).
