#!/usr/bin/env python3
"""Selective static QDQ INT8 experiment for Inflect decode graphs.

This does **not** replace production assets. It writes candidates under
``out/experimental-int8/<variant>/`` for on-device listening and TTFA comparison.

Default policy (from the latency plan):
  - Keep duration.onnx in float32 (timing errors become audible duration drift).
  - Quantize decode.onnx with static QDQ and representative calibration text.
  - Require device speed + listening before any adoption.

Push flow (same names the Android experimental probe expects)::

  python scripts/quantize_decode_experiment.py --model all
  adb push out/experimental-int8/nano \\
    /sdcard/Android/data/com.github.aljge.tensorspeak/files/experimental-ort/nano

Writes ``decode.onnx`` (INT8 QDQ) + float ``duration.onnx`` + ``REPORT.json``.
Optional ``--emit-wavs`` writes FP32 vs INT8 side-by-side WAVs under the variant dir.
``--profile`` selects quantization hyperparams for Gate B/C iteration.

See docs/LATENCY.md. FP16-on-CPU is intentionally not attempted.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import time
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "src"))

from inflect_sandbox.frontend import (  # noqa: E402
    SAMPLE_RATE,
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

LISTENING = [
    "Hello world.",
    "Dr. Chen paid $1,234.50 on 7/4/2026 at 3:05 PM.",
    "Bring pens, paper, etc. tomorrow.",
    "He lives in the U.S.A. now.",
    "The fluorescent light flickered in Saskatchewan.",
    "A long sentence with many commas, pauses, and clauses should still start quickly.",
    "Is this working? It really should be!",
]

# Named quantization profiles for the tuning ladder in docs/LATENCY.md / research plan.
PROFILES: dict[str, dict] = {
    "default": {
        "per_channel": True,
        "activation": "QInt8",
        "weight": "QInt8",
        "reduce_range": False,
        "op_types_to_quantize": None,
    },
    "per_tensor": {
        "per_channel": False,
        "activation": "QInt8",
        "weight": "QInt8",
        "reduce_range": False,
        "op_types_to_quantize": None,
    },
    "uint8_act": {
        "per_channel": True,
        "activation": "QUInt8",
        "weight": "QInt8",
        "reduce_range": False,
        "op_types_to_quantize": None,
    },
    "conv_only": {
        "per_channel": True,
        "activation": "QInt8",
        "weight": "QInt8",
        "reduce_range": False,
        "op_types_to_quantize": ["Conv"],
    },
    "reduce_range": {
        "per_channel": True,
        "activation": "QInt8",
        "weight": "QInt8",
        "reduce_range": True,
        "op_types_to_quantize": None,
    },
}


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


def _write_wav(path: Path, audio: np.ndarray) -> None:
    pcm = np.clip(audio, -1.0, 1.0)
    pcm16 = (pcm * 32767.0).astype(np.int16)
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(pcm16.tobytes())


def _quant_type(name: str):
    from onnxruntime.quantization import QuantType  # noqa: PLC0415

    return getattr(QuantType, name)


def quantize_decode(variant: str, *, profile: str, emit_wavs: bool) -> Path:
    from onnxruntime.quantization import (  # noqa: PLC0415
        CalibrationDataReader,
        QuantFormat,
        quantize_static,
    )

    if profile not in PROFILES:
        raise SystemExit(f"unknown profile {profile!r}; choose from {sorted(PROFILES)}")
    cfg = PROFILES[profile]

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

    dest_dir = OUT / variant if profile == "default" else OUT / f"{variant}-{profile}"
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = dest_dir / "decode.onnx"

    pipeline = InflectPipeline(variant=variant)
    feeds = [_decode_feeds(pipeline, text, seed=i) for i, text in enumerate(CALIBRATION)]
    shutil.copyfile(variant_root(variant) / "onnx" / "duration.onnx", dest_dir / "duration.onnx")

    kwargs: dict = {
        "model_input": str(source),
        "model_output": str(dest),
        "calibration_data_reader": Reader(feeds),
        "quant_format": QuantFormat.QDQ,
        "activation_type": _quant_type(cfg["activation"]),
        "weight_type": _quant_type(cfg["weight"]),
        "per_channel": cfg["per_channel"],
        "reduce_range": cfg["reduce_range"],
    }
    if cfg["op_types_to_quantize"] is not None:
        kwargs["op_types_to_quantize"] = cfg["op_types_to_quantize"]

    quantize_static(**kwargs)

    import onnxruntime as ort

    fp_sess = ort.InferenceSession(str(source), providers=["CPUExecutionProvider"])
    iq_sess = ort.InferenceSession(str(dest), providers=["CPUExecutionProvider"])

    # Warm + host decode timing on first calibration feed.
    for _ in range(3):
        fp_sess.run(None, feeds[0])
        iq_sess.run(None, feeds[0])
    fp_times: list[float] = []
    iq_times: list[float] = []
    for _ in range(15):
        t0 = time.perf_counter()
        fp_sess.run(None, feeds[0])
        fp_times.append((time.perf_counter() - t0) * 1000.0)
        t0 = time.perf_counter()
        iq_sess.run(None, feeds[0])
        iq_times.append((time.perf_counter() - t0) * 1000.0)

    metrics = []
    wav_dir = dest_dir / "listen"
    if emit_wavs:
        wav_dir.mkdir(parents=True, exist_ok=True)

    for i, text in enumerate(LISTENING):
        feed = _decode_feeds(pipeline, text, seed=100 + i)
        a = np.asarray(fp_sess.run(None, feed)[0], dtype=np.float32).reshape(-1)
        b = np.asarray(iq_sess.run(None, feed)[0], dtype=np.float32).reshape(-1)
        mae = float(np.mean(np.abs(a - b)))
        mx = float(np.max(np.abs(a - b)))
        corr = float(np.corrcoef(a, b)[0, 1]) if a.size > 1 else 0.0
        metrics.append(
            {
                "text": text,
                "wav_len": int(a.size),
                "mae": mae,
                "max_abs": mx,
                "corr": corr,
            }
        )
        if emit_wavs:
            stem = f"{i:02d}"
            _write_wav(wav_dir / f"{stem}-fp32.wav", a)
            _write_wav(wav_dir / f"{stem}-int8.wav", b)

    out = iq_sess.run(None, feeds[0])[0]
    report = {
        "variant": variant,
        "profile": profile,
        "profile_config": cfg,
        "source_bytes": source.stat().st_size,
        "int8_bytes": dest.stat().st_size,
        "size_reduction_pct": round(
            100.0 * (1.0 - dest.stat().st_size / source.stat().st_size), 1
        ),
        "calibration_utterances": len(CALIBRATION),
        "sample_wav_len": int(np.asarray(out).size),
        "host_decode_ms_p50_fp32": float(np.median(fp_times)),
        "host_decode_ms_p50_int8": float(np.median(iq_times)),
        "listening_metrics": metrics,
        "notes": [
            "Listening required before adoption.",
            "duration.onnx left in float32 by design.",
            "Reject if RTF/TTFA does not beat FP32 on device or audio regresses.",
            "Push dest_dir to device experimental-ort/<variant>/ to probe via SynthBench "
            "(files must be named duration.onnx and decode.onnx).",
        ],
    }
    (dest_dir / "REPORT.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))
    return dest


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--model", choices=[*VARIANTS, "all"], default="nano")
    ap.add_argument(
        "--profile",
        choices=sorted(PROFILES),
        default="default",
        help="quantization hyperparam preset",
    )
    ap.add_argument(
        "--emit-wavs",
        action="store_true",
        help="write FP32/INT8 side-by-side WAVs under listen/",
    )
    args = ap.parse_args()
    selected = VARIANTS if args.model == "all" else (args.model,)
    for variant in selected:
        print(f"\n=== quantize decode {variant} profile={args.profile} ===")
        quantize_decode(variant, profile=args.profile, emit_wavs=args.emit_wavs)
    print(f"\nartifacts under {OUT}")


if __name__ == "__main__":
    main()
