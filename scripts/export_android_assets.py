#!/usr/bin/env python3
"""Emit the assets the Android app needs, derived from the verified Python pipeline.

  android/app/src/main/assets/duration.onnx, decode.onnx   (copied, gitignored)
  android/app/src/main/assets/symbols.json                 (the token id table)
  android/app/src/test/resources/golden_tokens.json        (frontend fixtures)

The golden fixtures let a JVM unit test prove Phonemes.kt produces exactly the ids the
Python frontend produces, without needing eSpeak on the JVM side.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "src"))

from inflect_sandbox.frontend import (  # noqa: E402
    MODELS_ROOT,
    SAMPLE_RATE,
    phonemes_to_tokens,
    symbol_table,
    text_to_phonemes,
)

FIXTURE_SENTENCES = [
    "A small voice can still have something meaningful to say.",
    "Hello world.",
    "Is this working? It really should be!",
]

ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets"
TEST_RESOURCES = ROOT / "android" / "app" / "src" / "test" / "resources"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--skip-models", action="store_true", help="don't copy the .onnx graphs")
    args = ap.parse_args()

    ASSETS.mkdir(parents=True, exist_ok=True)
    TEST_RESOURCES.mkdir(parents=True, exist_ok=True)

    symbols = symbol_table()
    symbols_json = json.dumps(
        {
            "comment": "index == token id; id 0 is the blank interleaved by add_blank",
            "sample_rate": SAMPLE_RATE,
            "symbols": symbols,
        },
        ensure_ascii=False,
        indent=2,
    )
    # Assets for the app, test resources for the JVM unit test (no AssetManager there).
    (ASSETS / "symbols.json").write_text(symbols_json)
    (TEST_RESOURCES / "symbols.json").write_text(symbols_json)
    print(f"symbols.json: {len(symbols)} symbols")

    fixtures = []
    for sentence in FIXTURE_SENTENCES:
        phoneme_text = text_to_phonemes(sentence)
        tokens = phonemes_to_tokens(phoneme_text)[0].tolist()
        fixtures.append({"text": sentence, "phonemes": phoneme_text, "tokens": tokens})
        print(f"  {sentence!r} -> {len(tokens)} tokens")

    (TEST_RESOURCES / "golden_tokens.json").write_text(
        json.dumps(fixtures, ensure_ascii=False, indent=2)
    )

    if not args.skip_models:
        for name in ("duration.onnx", "decode.onnx"):
            source = MODELS_ROOT / "onnx" / name
            shutil.copyfile(source, ASSETS / name)
            print(f"  copied {name} ({source.stat().st_size / 1e6:.1f} MB)")

    print(f"\nwrote {ASSETS} and {TEST_RESOURCES}")


if __name__ == "__main__":
    main()
