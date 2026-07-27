"""Text -> phoneme id frontend.

The text->IPA step is delegated to the *upstream* frontend shipped in the model repo
(`inflect_vits_frontend.run_vits_frontend`), because it is bound to a specific eSpeak-ng
build plus a hand-tuned override table. Reimplementing it would silently drift and produce
subtly wrong audio, so we import it verbatim and only own the steps after it.

Everything below the phonemization boundary (symbol table, blank interleaving, sentence
chunking, boundary pauses) is implemented here and is the specification the Android port
in `android/` must match. See `docs/TENSOR_CONTRACT.md`.
"""

from __future__ import annotations

import logging
import re
import sys
from functools import lru_cache
from pathlib import Path

import numpy as np

logger = logging.getLogger(__name__)

MODELS_DIR = Path(__file__).resolve().parents[2] / "models"
DEFAULT_VARIANT = "micro"
VARIANTS = ("micro", "nano")

# Upstream frontend modules live under models/micro/ (shared by both graph variants).
FRONTEND_ROOT = MODELS_DIR / DEFAULT_VARIANT
# Back-compat alias: callers that only need the frontend still point at micro.
MODELS_ROOT = FRONTEND_ROOT

SAMPLE_RATE = 24_000


def variant_root(variant: str = DEFAULT_VARIANT) -> Path:
    """Return models/<variant>/ for ONNX graphs and upstream parity scripts."""
    if variant not in VARIANTS:
        raise ValueError(f"unknown model variant {variant!r}; expected one of {VARIANTS}")
    return MODELS_DIR / variant

# Pause inserted after a chunk, keyed by its final punctuation mark (seconds).
BOUNDARY_PAUSES = {
    "?": 0.20,
    "!": 0.18,
    ".": 0.15,
    ";": 0.12,
    ":": 0.10,
    ",": 0.07,
}
DEFAULT_PAUSE = 0.06
LEGACY_BOUNDARY_PAUSES = {
    "?": 0.28,
    "!": 0.24,
    ".": 0.22,
    ";": 0.16,
    ":": 0.13,
    ",": 0.09,
}
LEGACY_DEFAULT_PAUSE = 0.08

_SENTENCE_SPLIT = re.compile(r"(?<=[.!?;:])\s+")
_PROTECTED_DOT = "\uF000"
_ABBREVIATION_DOT = re.compile(
    r"\b(?:dr|mr|mrs|ms|prof|st|vs|etc|e\.g|i\.e)\.",
    re.IGNORECASE,
)
_INITIALISM_DOT = re.compile(r"\b(?:[A-Za-z]\.){2,}")


def _install_upstream_path(models_root: Path = FRONTEND_ROOT) -> None:
    """Put the downloaded model repo on sys.path so its frontend modules import."""
    if not (models_root / "inflect_vits_frontend.py").exists():
        raise SystemExit(
            f"upstream frontend not found under {models_root} - run scripts/fetch_model.py first"
        )
    for entry in (models_root / "runtime", models_root):
        text = str(entry)
        if text not in sys.path:
            sys.path.insert(0, text)


@lru_cache(maxsize=1)
def symbol_table() -> list[str]:
    """The VITS symbol table: index in this list *is* the token id."""
    _install_upstream_path()
    from text.symbols import symbols  # noqa: PLC0415 - needs the sys.path above

    return list(symbols)


@lru_cache(maxsize=1)
def _symbol_to_id() -> dict[str, int]:
    return {symbol: index for index, symbol in enumerate(symbol_table())}


def text_to_phonemes(text: str) -> str:
    """Run the upstream normalizer + eSpeak-ng phonemizer. Returns an IPA string."""
    _install_upstream_path()
    from inflect_vits_frontend import run_vits_frontend  # noqa: PLC0415

    return run_vits_frontend(text).phoneme_text


def sanitize_phonemes(phoneme_text: str) -> tuple[str, list[str]]:
    """Drop phonemes the 178-symbol table cannot represent.

    eSpeak-ng emits a handful of characters the VITS symbol table never had, and in
    practice one of them is common: U+0303 COMBINING TILDE, the nasal vowel in French
    loanwords that ordinary English text is full of - "croissant", "blanc",
    "denouement", "Provence". Because the mark trails its base vowel, dropping it leaves
    `ɑ̃` as `ɑ`, which is what an en-US voice approximates anyway.

    The alternative - what this used to do - was to raise and fail the *entire*
    utterance over one character, so a single "croissant" silenced a whole paragraph.

    Returns the sanitized text plus the characters that were dropped, so callers can
    log them. Android's `PhonemeTokenizer.sanitize` must stay identical to this.
    """
    symbol_to_id = _symbol_to_id()
    kept = [c for c in phoneme_text if c in symbol_to_id]
    dropped = sorted({c for c in phoneme_text if c not in symbol_to_id})
    return "".join(kept), dropped


def phonemes_to_tokens(phoneme_text: str) -> np.ndarray:
    """Map IPA characters to ids and interleave the blank id 0 (`add_blank: true`).

    Result is shaped [1, 2 * len(phonemes) + 1] as `duration.onnx` expects for `tokens`.
    Unrepresentable characters are dropped rather than raising; see [sanitize_phonemes].
    """
    symbol_to_id = _symbol_to_id()
    sanitized, dropped = sanitize_phonemes(phoneme_text)
    if dropped:
        logger.warning(
            "dropped %d phoneme(s) outside the symbol table: %r", len(dropped), dropped
        )

    sequence = [symbol_to_id[symbol] for symbol in sanitized]
    if not sequence:
        raise ValueError("The text frontend produced no speakable tokens.")

    with_blanks = np.zeros(len(sequence) * 2 + 1, dtype=np.int64)
    with_blanks[1::2] = sequence
    return with_blanks[None, :]


def split_text(text: str, limit: int = 280, *, enhanced: bool = True) -> list[str]:
    """Split into synthesis chunks: sentence-first, then on punctuation, then whitespace."""
    normalized = " ".join(text.split())
    if enhanced:
        protected = _protect_sentence_dots(normalized)
        sentences = [
            _restore_sentence_dots(part.strip())
            for part in _SENTENCE_SPLIT.split(protected)
            if part.strip()
        ]
    else:
        sentences = [part.strip() for part in _SENTENCE_SPLIT.split(normalized) if part.strip()]

    chunks: list[str] = []
    for sentence in sentences or [normalized]:
        while len(sentence) > limit:
            search = sentence[: limit + 1]
            punctuation = max(search.rfind(mark) for mark in (",", ";", ":"))
            split_at = (
                punctuation + 1
                if punctuation >= limit // 2
                else sentence.rfind(" ", 0, limit + 1)
            )
            if split_at < limit // 2:
                split_at = limit
            chunks.append(sentence[:split_at].strip())
            sentence = sentence[split_at:].strip()
        if sentence:
            chunks.append(sentence)
    return chunks


def _protect_sentence_dots(text: str) -> str:
    """Protect period-style abbreviations before sentence splitting."""
    protected = text
    protected = _ABBREVIATION_DOT.sub(
        lambda match: match.group(0).replace(".", _PROTECTED_DOT), protected
    )
    protected = _INITIALISM_DOT.sub(
        lambda match: match.group(0).replace(".", _PROTECTED_DOT), protected
    )
    return protected


def _restore_sentence_dots(text: str) -> str:
    return text.replace(_PROTECTED_DOT, ".")


def boundary_pause_seconds(chunk: str, *, enhanced: bool = True) -> float:
    ending = chunk.rstrip()[-1:] if chunk.strip() else ""
    if enhanced:
        return BOUNDARY_PAUSES.get(ending, DEFAULT_PAUSE)
    return LEGACY_BOUNDARY_PAUSES.get(ending, LEGACY_DEFAULT_PAUSE)
