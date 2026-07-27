# Model attribution

TensorSpeak’s speech quality comes from **[Inflect](https://github.com/owenawsong/Inflect)**, an independent
ultra-lightweight local TTS project by **[Owen Song](https://github.com/owenawsong)**
([@owenawsong](https://github.com/owenawsong); models on [Hugging Face](https://huggingface.co/owensong)).
Owen’s [Inflect v2 evaluation writeup](https://huggingface.co/owensong/Inflect-Micro-v2/blob/main/docs/EVALUATION.md)
documents benchmarks, listening tests, and runtime for Micro v2 and Nano v2; the
[Micro v2](https://huggingface.co/owensong/Inflect-Micro-v2) and
[Nano v2](https://huggingface.co/owensong/Inflect-Nano-v2) model cards summarize the release.

The Android APK redistributes the **official ONNX exports** from two Hugging Face repos (not the PyTorch
checkpoints). Both are **Apache License 2.0** and may be redistributed with the license text intact.

| Variant | Hugging Face | Params | Role in APK |
| --- | --- | --- | --- |
| Micro (default) | [owensong/Inflect-Micro-v2-ONNX](https://huggingface.co/owensong/Inflect-Micro-v2-ONNX) | 9.36M | `assets/micro/{duration,decode}.onnx` |
| Nano | [owensong/Inflect-Nano-v2-ONNX](https://huggingface.co/owensong/Inflect-Nano-v2-ONNX) | 3.96M | `assets/nano/{duration,decode}.onnx` |

**Credit:** Inflect models and reference inference by **[Owen Song](https://github.com/owenawsong)**
([@owenawsong](https://github.com/owenawsong); [Inflect](https://github.com/owenawsong/Inflect)).
Official ONNX packaging by Owen’s Hugging Face releases; conversion tooling by **Robert Bak**
([`webtts-inflect`](https://github.com/robertbak/webtts-inflect)). Each download includes `onnx/SOURCE.json`
with revision hashes and `"license": "Apache-2.0"`.

**Not committed:** The `.onnx` files and trimmed `espeak-ng-data/` are gitignored. After
`python scripts/fetch_model.py`, run:

```bash
python scripts/export_android_assets.py --espeak-data
```

That copies the graphs **and** each variant’s `LICENSE` (plus `SOURCE.json` when present)
into `android/app/src/main/assets/<variant>/`. The Apache-2.0 LICENSE files are intended to
ship in the APK next to the graphs; only `*.onnx` remains gitignored under assets.

See also [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) and [TENSOR_CONTRACT.md](TENSOR_CONTRACT.md).
