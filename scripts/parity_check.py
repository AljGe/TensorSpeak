#!/usr/bin/env python3
"""Assert our pipeline matches the upstream reference implementation sample-for-sample.

This is the acceptance test for Stage 1: a self-consistent but subtly wrong pipeline is the
main risk, and only a direct comparison against `models/onnx/inference_onnx.py` catches it.
"""

from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "src"))

from inflect_sandbox.frontend import MODELS_ROOT  # noqa: E402
from inflect_sandbox.pipeline import InflectPipeline  # noqa: E402

SENTENCES = [
    "A small voice can still have something meaningful to say.",
    "Dr. Smith paid $42.50 on March 3rd, 2026; then he left.",
    "Is this working? It really should be! Let us find out.",
]


def load_upstream():
    """Import models/onnx/inference_onnx.py as a module (it is not importable by name)."""
    path = MODELS_ROOT / "onnx" / "inference_onnx.py"
    spec = importlib.util.spec_from_file_location("upstream_inference_onnx", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--tolerance", type=float, default=1e-6)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    upstream = load_upstream().InflectONNX(model_dir=MODELS_ROOT, provider="cpu")
    ours = InflectPipeline(provider="cpu")

    failures = []
    for sentence in SENTENCES:
        _, expected = upstream.synthesize(sentence, seed=args.seed)
        actual = ours.synthesize(sentence, seed=args.seed).waveform

        if expected.shape != actual.shape:
            failures.append(f"{sentence!r}: shape {actual.shape} != upstream {expected.shape}")
            continue
        max_abs = float(np.abs(expected - actual).max())
        status = "ok " if max_abs <= args.tolerance else "FAIL"
        print(f"[{status}] max|diff| = {max_abs:.3e}  n={actual.size}  {sentence!r}")
        if max_abs > args.tolerance:
            failures.append(f"{sentence!r}: max|diff| {max_abs:.3e} > {args.tolerance:.0e}")

    if failures:
        raise SystemExit("PARITY FAILED:\n  " + "\n  ".join(failures))
    print(f"\nparity ok on {len(SENTENCES)} sentences (tolerance {args.tolerance:.0e})")


if __name__ == "__main__":
    main()
