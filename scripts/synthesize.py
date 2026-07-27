#!/usr/bin/env python3
"""Synthesize speech and log every intermediate shape / timing."""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from inflect_sandbox.frontend import DEFAULT_VARIANT, VARIANTS  # noqa: E402
from inflect_sandbox.pipeline import InflectPipeline  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--text", required=True)
    ap.add_argument("--output", type=Path, default=Path("out/sample.wav"))
    ap.add_argument("--model", choices=VARIANTS, default=DEFAULT_VARIANT)
    ap.add_argument("--provider", default="cpu", choices=["cpu", "cuda", "directml"])
    ap.add_argument("--speed", type=float, default=1.0)
    ap.add_argument("--variation", type=float, default=0.667)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    started = time.perf_counter()
    pipeline = InflectPipeline(provider=args.provider, variant=args.model)
    load_ms = (time.perf_counter() - started) * 1000.0
    print(f"sessions loaded in {load_ms:.0f} ms ({args.provider}, {args.model})")

    started = time.perf_counter()
    result = pipeline.synthesize(
        args.text, speed=args.speed, variation=args.variation, seed=args.seed
    )
    total_ms = (time.perf_counter() - started) * 1000.0

    for index, t in enumerate(result.traces):
        print(f"\nchunk {index}: {t.text!r}")
        print(f"  phonemes    : {t.phoneme_text}")
        print(f"  tokens      : [1, {t.token_count}]  (blank-interleaved)")
        print(f"  mel_len     : {t.mel_len}")
        print(f"  wav_len     : {t.wav_len}   ({t.wav_len / result.sample_rate:.2f} s)")
        print(f"  duration.onnx: {t.duration_ms:6.1f} ms")
        print(f"  decode.onnx  : {t.decode_ms:6.1f} ms")

    audio_seconds = result.waveform.size / result.sample_rate
    print(
        f"\ntotal: {total_ms:.0f} ms for {audio_seconds:.2f} s of audio "
        f"(RTF {total_ms / 1000.0 / audio_seconds:.3f})"
    )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    sf.write(args.output, result.waveform, result.sample_rate)
    print(f"wrote {args.output} ({result.sample_rate} Hz mono)")


if __name__ == "__main__":
    main()
