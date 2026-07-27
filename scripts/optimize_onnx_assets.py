#!/usr/bin/env python3
"""Offline ORT graph optimization / ORT-format conversion for Android experiments.

Produces CPU-targeted artifacts under ``out/experimental-ort/<variant>/``:

  duration.onnx / decode.onnx   - optimized ONNX (ALL_OPT applied offline)
  duration.ort  / decode.ort    - ORT flatbuffers format when the converter is available

These are **not** shipped in the APK by default. Push a variant directory to the device:

  adb push out/experimental-ort/nano \\
    /sdcard/Android/data/com.github.aljge.tensorspeak/files/experimental-ort/nano

``SynthesisBenchmark`` probes that path and reports load/TTFA if present.

Acceptance gate: keep online ALL_OPT ONNX as production until cold-load and warm TTFA
improve without audio regressions on device. See docs/LATENCY.md.
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "src"))

from inflect_sandbox.frontend import VARIANTS, variant_root  # noqa: E402

OUT = ROOT / "out" / "experimental-ort"


def _try_convert_to_ort(optimized_onnx: Path, dest: Path) -> None:
    ort_path = dest / (optimized_onnx.stem + ".ort")
    try:
        from onnxruntime.tools.offline_optimizer import convert_onnx_models_to_ort as convert
    except ImportError:
        try:
            from onnxruntime.tools.convert_onnx_models_to_ort import (  # type: ignore
                convert_onnx_models_to_ort as convert,
            )
        except ImportError as error:
            print(f"  WARN: ORT-format conversion unavailable ({error})")
            return

    try:
        convert(str(optimized_onnx), output_dir=str(dest))
    except TypeError:
        # Older signature variants.
        convert(str(optimized_onnx))
    produced = list(dest.glob(optimized_onnx.stem + "*.ort"))
    if not produced:
        print(f"  WARN: converter ran but no .ort for {optimized_onnx.name}")
        return
    produced[0].replace(ort_path)
    print(f"  wrote {ort_path.relative_to(ROOT)} ({ort_path.stat().st_size / 1e6:.2f} MB)")


def convert_variant(variant: str, *, ort_format: bool, optimize: bool) -> Path:
    import onnxruntime as ort

    source_dir = variant_root(variant) / "onnx"
    dest = OUT / variant
    dest.mkdir(parents=True, exist_ok=True)

    so = ort.SessionOptions()
    so.graph_optimization_level = (
        ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        if optimize
        else ort.GraphOptimizationLevel.ORT_DISABLE_ALL
    )

    for name in ("duration.onnx", "decode.onnx"):
        source = source_dir / name
        if not source.exists():
            raise SystemExit(f"missing {source} - run scripts/fetch_model.py --model {variant}")

        optimized_onnx = dest / name
        so.optimized_model_filepath = str(optimized_onnx)
        session = ort.InferenceSession(
            str(source),
            sess_options=so,
            providers=["CPUExecutionProvider"],
        )
        del session
        if not optimized_onnx.exists():
            # Some ORT builds ignore optimized_model_filepath; fall back to a plain copy.
            shutil.copyfile(source, optimized_onnx)
            print(f"  copied {optimized_onnx.relative_to(ROOT)} (optimizer did not emit a file)")
        else:
            print(
                f"  wrote {optimized_onnx.relative_to(ROOT)} "
                f"({optimized_onnx.stat().st_size / 1e6:.2f} MB)"
            )

        if ort_format:
            _try_convert_to_ort(optimized_onnx, dest)

    source_json = source_dir / "SOURCE.json"
    if source_json.exists():
        shutil.copyfile(source_json, dest / "SOURCE.json")
    return dest


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--model", choices=[*VARIANTS, "all"], default="all")
    ap.add_argument("--no-optimize", action="store_true", help="skip offline ALL_OPT")
    ap.add_argument("--no-ort-format", action="store_true", help="skip .ort conversion")
    args = ap.parse_args()

    selected = VARIANTS if args.model == "all" else (args.model,)
    print(f"onnxruntime {__import__('onnxruntime').__version__}")
    for variant in selected:
        print(f"\n=== {variant} ===")
        convert_variant(
            variant,
            ort_format=not args.no_ort_format,
            optimize=not args.no_optimize,
        )
    print(f"\nartifacts under {OUT}")


if __name__ == "__main__":
    main()
