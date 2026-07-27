"""The two-stage ONNX pipeline: tokens -> duration.onnx -> decode.onnx -> 24 kHz PCM.

Contract (generated into `docs/TENSOR_CONTRACT.md` by scripts/inspect_graphs.py):

  duration.onnx
    in : tokens int64[1, text_len], lengths int64[1], length_scale float32[]
    out: m_p_exp float32[1, 192, mel_len], logs_p_exp float32[1, 192, mel_len],
         y_mask float32[1, 1, mel_len]
  decode.onnx
    in : m_p_exp, logs_p_exp, y_mask, zp_noise float32[1, 192, mel_len], noise_scale float32[]
    out: waveform float32[1, 1, wav_len]

Note that duration expansion (length regulation) happens *inside* duration.onnx - its
outputs are already at `mel_len`. The caller only has to draw `zp_noise`. This is the single
most important fact for the Android port.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import onnxruntime as ort

from .frontend import (
    MODELS_ROOT,
    SAMPLE_RATE,
    boundary_pause_seconds,
    phonemes_to_tokens,
    split_text,
    text_to_phonemes,
)

PROVIDER_ALIASES = {
    "cpu": "CPUExecutionProvider",
    "cuda": "CUDAExecutionProvider",
    "directml": "DmlExecutionProvider",
}

DURATION_OUTPUTS = ["m_p_exp", "logs_p_exp", "y_mask"]


@dataclass
class ChunkTrace:
    """Per-chunk shapes and timings - the log Stage 1 exists to produce."""

    text: str
    phoneme_text: str
    token_count: int
    mel_len: int
    wav_len: int
    duration_ms: float
    decode_ms: float


@dataclass
class SynthesisResult:
    sample_rate: int
    waveform: np.ndarray
    traces: list[ChunkTrace] = field(default_factory=list)


def resolve_provider(name: str) -> str:
    provider = PROVIDER_ALIASES.get(name.lower(), name)
    available = ort.get_available_providers()
    if provider not in available:
        raise ValueError(f"provider {provider!r} unavailable; installed: {available}")
    return provider


def edge_fade(waveform: np.ndarray, milliseconds: float = 5.0) -> np.ndarray:
    """Ramp the first/last few ms to zero so concatenated chunks don't click."""
    frames = min(round(SAMPLE_RATE * milliseconds / 1000.0), waveform.size // 2)
    if frames <= 0:
        return waveform
    output = waveform.copy()
    ramp = np.linspace(0.0, 1.0, frames, endpoint=True, dtype=np.float32)
    output[:frames] *= ramp
    output[-frames:] *= ramp[::-1]
    return output


class InflectPipeline:
    def __init__(self, models_root: Path = MODELS_ROOT, provider: str = "cpu") -> None:
        onnx_dir = Path(models_root) / "onnx"
        selected = resolve_provider(provider)
        providers = [selected]
        if selected != "CPUExecutionProvider":
            providers.append("CPUExecutionProvider")

        self.duration = ort.InferenceSession(str(onnx_dir / "duration.onnx"), providers=providers)
        self.decode = ort.InferenceSession(str(onnx_dir / "decode.onnx"), providers=providers)

    def synthesize_chunk(
        self, text: str, *, speed: float, variation: float, seed: int
    ) -> tuple[np.ndarray, ChunkTrace]:
        phoneme_text = text_to_phonemes(text)
        tokens = phonemes_to_tokens(phoneme_text)

        started = time.perf_counter()
        m_p_exp, logs_p_exp, y_mask = self.duration.run(
            DURATION_OUTPUTS,
            {
                "tokens": tokens,
                "lengths": np.asarray([tokens.shape[1]], dtype=np.int64),
                # length_scale is inverse speed: >1 stretches, <1 compresses
                "length_scale": np.asarray(1.0 / speed, dtype=np.float32),
            },
        )
        duration_ms = (time.perf_counter() - started) * 1000.0

        # Seeded so parity checks and A/B listening tests are reproducible.
        rng = np.random.default_rng(seed)
        zp_noise = rng.standard_normal(m_p_exp.shape, dtype=np.float32)

        started = time.perf_counter()
        waveform = self.decode.run(
            ["waveform"],
            {
                "m_p_exp": m_p_exp,
                "logs_p_exp": logs_p_exp,
                "y_mask": y_mask,
                "zp_noise": zp_noise,
                "noise_scale": np.asarray(variation, dtype=np.float32),
            },
        )[0]
        decode_ms = (time.perf_counter() - started) * 1000.0

        audio = edge_fade(np.asarray(waveform, dtype=np.float32).reshape(-1))
        trace = ChunkTrace(
            text=text,
            phoneme_text=phoneme_text,
            token_count=int(tokens.shape[1]),
            mel_len=int(m_p_exp.shape[2]),
            wav_len=int(audio.size),
            duration_ms=duration_ms,
            decode_ms=decode_ms,
        )
        return audio, trace

    def synthesize(
        self,
        text: str,
        *,
        speed: float = 1.0,
        variation: float = 0.667,
        seed: int = 0,
    ) -> SynthesisResult:
        normalized = " ".join(text.split())
        if not normalized:
            raise ValueError("Text must not be empty.")
        if not 0.5 <= speed <= 2.0:
            raise ValueError("speed must be between 0.5 and 2.0")
        if not 0.0 <= variation <= 1.0:
            raise ValueError("variation must be between 0.0 and 1.0")

        chunks = split_text(normalized)
        pieces: list[np.ndarray] = []
        traces: list[ChunkTrace] = []

        for index, chunk in enumerate(chunks):
            if index:
                pause = boundary_pause_seconds(chunks[index - 1])
                pieces.append(np.zeros(round(SAMPLE_RATE * pause), dtype=np.float32))
            # seed advances per chunk so adjacent chunks don't share a noise draw
            audio, trace = self.synthesize_chunk(
                chunk, speed=speed, variation=variation, seed=seed + index
            )
            pieces.append(audio)
            traces.append(trace)

        waveform = np.clip(np.concatenate(pieces), -1.0, 1.0)
        return SynthesisResult(sample_rate=SAMPLE_RATE, waveform=waveform, traces=traces)
