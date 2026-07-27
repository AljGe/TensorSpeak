#!/usr/bin/env python3
"""Export fixed-seed listening samples for blinded A/B checks."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import soundfile as sf

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "src"))

from inflect_sandbox.frontend import DEFAULT_VARIANT, VARIANTS  # noqa: E402
from inflect_sandbox.pipeline import InflectPipeline  # noqa: E402


def read_corpus(path: Path) -> list[str]:
    return [line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", choices=VARIANTS, default=DEFAULT_VARIANT)
    parser.add_argument("--speed", type=float, default=1.0)
    parser.add_argument("--variation", type=float, default=0.62)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument(
        "--corpus",
        type=Path,
        default=ROOT / "docs" / "LISTENING_CORPUS.txt",
    )
    parser.add_argument("--output-dir", type=Path, default=ROOT / "out" / "listening")
    args = parser.parse_args()

    rows = read_corpus(args.corpus)
    pipeline = InflectPipeline(provider="cpu", variant=args.model)
    out_dir = args.output_dir / args.model / f"speed-{args.speed:.2f}_var-{args.variation:.2f}"
    out_dir.mkdir(parents=True, exist_ok=True)

    for idx, text in enumerate(rows, start=1):
        result = pipeline.synthesize(
            text=text,
            speed=args.speed,
            variation=args.variation,
            seed=args.seed + idx,
        )
        target = out_dir / f"{idx:02d}.wav"
        sf.write(target, result.waveform, result.sample_rate)
        print(f"{target}: {text}")


if __name__ == "__main__":
    main()
