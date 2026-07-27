# Model attribution

The Android APK redistributes ONNX graphs from two Hugging Face packages. Both are
**Apache License 2.0** and may be redistributed with the license text intact.

| Variant | Hugging Face | Params | Role in APK |
| --- | --- | --- | --- |
| Micro (default) | [owensong/Inflect-Micro-v2-ONNX](https://huggingface.co/owensong/Inflect-Micro-v2-ONNX) | 9.36M | `assets/micro/{duration,decode}.onnx` |
| Nano | [owensong/Inflect-Nano-v2-ONNX](https://huggingface.co/owensong/Inflect-Nano-v2-ONNX) | 3.96M | `assets/nano/{duration,decode}.onnx` |

**Credit:** Official packaging by **Owen Song**. ONNX conversion tooling by **Robert Bak**
(`webtts-inflect`). Each download includes `onnx/SOURCE.json` with revision hashes and
`"license": "Apache-2.0"`.

**Not committed:** The `.onnx` files and trimmed `espeak-ng-data/` are gitignored. After
`python scripts/fetch_model.py`, run:

```bash
python scripts/export_android_assets.py --espeak-data
```

That copies the graphs **and** each variant’s `LICENSE` (plus `SOURCE.json` when present)
into `android/app/src/main/assets/<variant>/`. The Apache-2.0 LICENSE files are intended to
ship in the APK next to the graphs; only `*.onnx` remains gitignored under assets.

See also [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) and [TENSOR_CONTRACT.md](TENSOR_CONTRACT.md).
