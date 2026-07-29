#!/usr/bin/env python3
"""Emit the assets the Android app needs, derived from the verified Python pipeline.

  android/app/src/main/assets/<variant>/{duration,decode}.onnx  (copied, gitignored)
  android/app/src/main/assets/<variant>/LICENSE                 (Apache-2.0 from upstream)
  android/app/src/main/assets/<variant>/SOURCE.json             (when present upstream)
  android/app/src/main/assets/symbols.json                      (the token id table)
  android/app/src/main/assets/espeak-ng-data/                   (--espeak-data, gitignored)
  android/app/src/test/resources/golden_tokens.json             (frontend fixtures)

The golden fixtures let a JVM unit test prove Phonemes.kt produces exactly the ids the
Python frontend produces, without needing eSpeak on the JVM side.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "src"))

from inflect_sandbox.frontend import (  # noqa: E402
    SAMPLE_RATE,
    VARIANTS,
    phonemes_to_tokens,
    symbol_table,
    text_to_phonemes,
    variant_root,
)

FIXTURE_SENTENCES = [
    "A small voice can still have something meaningful to say.",
    "Hello world.",
    "Is this working? It really should be!",
]

ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets"
TEST_RESOURCES = ROOT / "android" / "app" / "src" / "test" / "resources"

# The full espeak-ng-data shipped by espeakng-loader is 19 MB, almost all of it dictionaries
# for languages we never load. These are the entries en-us actually needs. Anything matching
# a glob here is copied; everything else is dropped.
ESPEAK_DATA_KEEP = (
    "phontab",
    "phonindex",
    "phondata",
    "phondata-manifest",
    "intonations",
    "en_dict",
    "lang/gmw/en*",
    "voices/!v/*",
)


def export_espeak_data(destination: Path) -> None:
    """Copy the trimmed espeak-ng-data that EspeakNative initializes against.

    Deliberately sourced from espeakng-loader rather than a system/nix espeak-ng: that is the
    build the Python sandbox phonemizes with (see _configure_espeak in the upstream frontend),
    so it is the parity reference. The vendored native espeak-ng is pinned to the same 1.52.0.
    """
    import espeakng_loader

    source = Path(espeakng_loader.get_data_path())
    if destination.exists():
        shutil.rmtree(destination)

    selected: list[Path] = []
    for pattern in ESPEAK_DATA_KEEP:
        matches = sorted(path for path in source.glob(pattern) if path.is_file())
        if not matches:
            raise SystemExit(f"espeak-ng-data pattern matched nothing: {pattern!r} under {source}")
        selected.extend(matches)

    manifest = []
    total = 0
    for path in selected:
        relative = path.relative_to(source)
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        manifest.append({"path": str(relative), "size": path.stat().st_size, "sha256": digest})
        total += path.stat().st_size

    (destination / "MANIFEST.json").write_text(
        json.dumps(
            {
                "source": str(source),
                "espeakng_loader": getattr(espeakng_loader, "__version__", "unknown"),
                "keep_patterns": list(ESPEAK_DATA_KEEP),
                "files": manifest,
            },
            indent=2,
        )
    )
    print(f"  espeak-ng-data: {len(manifest)} files, {total / 1e6:.1f} MB (from {source})")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--skip-models", action="store_true", help="don't copy the .onnx graphs")
    ap.add_argument(
        "--espeak-data",
        action="store_true",
        help="also copy the trimmed espeak-ng-data into assets (needed by EspeakPhonemeSource)",
    )
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

    # Drop any pre-variant flat graphs left over from older exports.
    for stale in ("duration.onnx", "decode.onnx"):
        flat = ASSETS / stale
        if flat.exists():
            flat.unlink()
            print(f"  removed stale flat asset {stale}")

    if args.skip_models:
        # Slim APK: graphs ship as GitHub Release ZIPs (see pack_model_assets.py).
        for variant in VARIANTS:
            dest_dir = ASSETS / variant
            for name in ("duration.onnx", "decode.onnx"):
                path = dest_dir / name
                if path.exists():
                    path.unlink()
                    print(f"  removed {variant}/{name} (--skip-models)")
    else:
        for variant in VARIANTS:
            dest_dir = ASSETS / variant
            dest_dir.mkdir(parents=True, exist_ok=True)
            root = variant_root(variant)
            onnx_dir = root / "onnx"
            for name in ("duration.onnx", "decode.onnx"):
                source = onnx_dir / name
                if not source.exists():
                    raise SystemExit(
                        f"missing {source} - run scripts/fetch_model.py --model {variant}"
                    )
                shutil.copyfile(source, dest_dir / name)
                print(f"  copied {variant}/{name} ({source.stat().st_size / 1e6:.1f} MB)")

            # Apache-2.0 notice must ship with redistributed graphs (not gitignored).
            license_src = root / "LICENSE"
            if not license_src.exists():
                raise SystemExit(
                    f"missing {license_src} - run scripts/fetch_model.py --model {variant}"
                )
            shutil.copyfile(license_src, dest_dir / "LICENSE")
            print(f"  copied {variant}/LICENSE")

            source_json = onnx_dir / "SOURCE.json"
            if source_json.exists():
                shutil.copyfile(source_json, dest_dir / "SOURCE.json")
                print(f"  copied {variant}/SOURCE.json")

    if args.espeak_data:
        export_espeak_data(ASSETS / "espeak-ng-data")

    print(f"\nwrote {ASSETS} and {TEST_RESOURCES}")


if __name__ == "__main__":
    main()
