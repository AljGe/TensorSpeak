#!/usr/bin/env python3
"""Selective static QDQ INT8 experiment for Inflect decode graphs.

This does **not** replace production assets. It writes candidates under
``out/experimental-int8/<variant>/`` for on-device listening and TTFA comparison.

Default policy (from the latency plan):
  - Keep duration.onnx in float32 (timing errors become audible duration drift).
  - Quantize decode.onnx with static QDQ and representative calibration text.
  - Require device speed + listening before any adoption.

See docs/LATENCY.md. FP16-on-CPU is intentionally not attempted.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "src"))

from inflect_sandbox.frontend import (  # noqa: E402
    VARIANTS,
    phonemes_to_tokens,
    split_text,
    text_to_phonemes,
    variant_root,
)
from inflect_sandbox.pipeline import DEFAULT_VARIATION, InflectPipeline  # noqa: E402

OUT = ROOT / "out" / "experimental-int8"

CALIBRATION = [
    "Hello world.",
    "A small voice can still have something meaningful to say.",
    "Dr. Chen paid $1,234.50 on 7/4/2026 at 3:05 PM.",
    "Bring pens, paper, etc. tomorrow.",
    "The fluorescent light flickered in Saskatchewan.",
    "Is this working? It really should be!",
    "Text to speech on a phone is a latency problem before it is a quality problem.",
]


def _decode_feeds(
    pipeline: InflectPipeline, text: str, *, seed: int = 0
) -> dict[str, np.ndarray]:
    chunk = split_text(text)[0]
    phonemes = text_to_phonemes(chunk)
    tokens = phonemes_to_tokens(phonemes)
    m_p_exp, logs_p_exp, y_mask = pipeline.duration.run(
        ["m_p_exp", "logs_p_exp", "y_mask"],
        {
            "tokens": tokens,
            "lengths": np.asarray([tokens.shape[1]], dtype=np.int64),
            "length_scale": np.asarray(1.0, dtype=np.float32),
        },
    )
    rng = np.random.default_rng(seed)
    zp_noise = rng.standard_normal(m_p_exp.shape, dtype=np.float32)
    variation = DEFAULT_VARIATION.get(pipeline.variant, 0.6)
    return {
        "m_p_exp": m_p_exp,
        "logs_p_exp": logs_p_exp,
        "y_mask": y_mask,
        "zp_noise": zp_noise,
        "noise_scale": np.asarray(variation, dtype=np.float32),
    }


def quantize_decode(variant: str) -> Path:
    from onnxruntime.quantization import (  # noqa: PLC0415
        CalibrationDataReader,
        QuantFormat,
        QuantType,
        quantize_static,
    )

    class Reader(CalibrationDataReader):
        def __init__(self, feeds: list[dict[str, np.ndarray]]):
            self._feeds = feeds
            self._index = 0

        def get_next(self):
            if self._index >= len(self._feeds):
                return None
            item = self._feeds[self._index]
            self._index += 1
            return item

    source = variant_root(variant) / "onnx" / "decode.onnx"
    if not source.exists():
        raise SystemExit(f"missing {source}")

    dest_dir = OUT / variant
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = dest_dir / "decode.onnx"

    pipeline = InflectPipeline(variant=variant)
    feeds = [_decode_feeds(pipeline, text, seed=i) for i, text in enumerate(CALIBRATION)]
    shutil.copyfile(variant_root(variant) / "onnx" / "duration.onnx", dest_dir / "duration.onnx")

    quantize_static(
        model_input=str(source),
        model_output=str(dest),
        calibration_data_reader=Reader(feeds),
        quant_format=QuantFormat.QDQ,
        activation_type=QuantType.QInt8,
        weight_type=QuantType.QInt8,
        per_channel=True,
    )

    import onnxruntime as ort

    sess = ort.InferenceSession(str(dest), providers=["CPUExecutionProvider"])
    out = sess.run(None, feeds[0])[0]
    report = {
        "variant": variant,
        "source_bytes": source.stat().st_size,
        "int8_bytes": dest.stat().st_size,
        "calibration_utterances": len(CALIBRATION),
        "sample_wav_len": int(np.asarray(out).size),
        "notes": [
            "Listening required before adoption.",
            "duration.onnx left in float32 by design.",
            "Reject if RTF/TTFA does not beat FP32 on device or audio regresses.",
            "Push dest_dir to device experimental-ort/<variant>/ to probe via SynthBench.",
        ],
    }
    (dest_dir / "REPORT.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))
    return dest


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--model", choices=[*VARIANTS, "all"], default="nano")
    args = ap.parse_args()
    selected = VARIANTS if args.model == "all" else (args.model,)
    for variant in selected:
        print(f"\n=== quantize decode {variant} ===")
        quantize_decode(variant)
    print(f"\nartifacts under {OUT}")


if __name__ == "__main__":
    main()
