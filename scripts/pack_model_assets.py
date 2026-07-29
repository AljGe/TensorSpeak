#!/usr/bin/env python3
"""Pack Inflect ONNX graphs into per-variant ZIPs for GitHub Releases.

Writes:
  out/model-packs/TensorSpeak-model-{micro,nano}.zip
  android/app/src/main/assets/model_manifest.json

Each ZIP contains flat entries:
  duration.onnx, decode.onnx, LICENSE, checksums.sha256, and SOURCE.json when present.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "src"))

from inflect_sandbox.frontend import VARIANTS, variant_root  # noqa: E402

ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets"
OUT_DIR = ROOT / "out" / "model-packs"
REPO = "AljGe/TensorSpeak"
URL_TEMPLATE = f"https://github.com/{REPO}/releases/download/v{{version}}/{{assetName}}"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def pack_variant(variant: str, out_dir: Path) -> dict:
    root = variant_root(variant)
    onnx_dir = root / "onnx"
    license_src = root / "LICENSE"
    if not license_src.exists():
        raise SystemExit(f"missing {license_src} - run scripts/fetch_model.py --model {variant}")

    members: list[tuple[Path, str]] = []
    for name in ("duration.onnx", "decode.onnx"):
        source = onnx_dir / name
        if not source.exists():
            raise SystemExit(f"missing {source} - run scripts/fetch_model.py --model {variant}")
        members.append((source, name))

    members.append((license_src, "LICENSE"))

    checksums = onnx_dir / "checksums.sha256"
    if checksums.exists():
        members.append((checksums, "checksums.sha256"))

    source_json = onnx_dir / "SOURCE.json"
    if source_json.exists():
        members.append((source_json, "SOURCE.json"))

    asset_name = f"TensorSpeak-model-{variant}.zip"
    zip_path = out_dir / asset_name
    out_dir.mkdir(parents=True, exist_ok=True)
    if zip_path.exists():
        zip_path.unlink()

    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for source, arcname in members:
            zf.write(source, arcname)

    digest = sha256_file(zip_path)
    size = zip_path.stat().st_size
    print(f"  {asset_name}: {size / 1e6:.1f} MB sha256={digest[:16]}…")
    return {
        "assetName": asset_name,
        "zipSha256": digest,
        "approxBytes": size,
    }


def write_manifest(variants: dict[str, dict], destination: Path) -> None:
    payload = {
        "repo": REPO,
        "urlTemplate": URL_TEMPLATE,
        "variants": variants,
    }
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(payload, indent=2) + "\n")
    print(f"wrote {destination}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--out",
        type=Path,
        default=OUT_DIR,
        help="directory for TensorSpeak-model-*.zip (default: out/model-packs)",
    )
    ap.add_argument(
        "--manifest",
        type=Path,
        default=ASSETS / "model_manifest.json",
        help="path for model_manifest.json",
    )
    ap.add_argument(
        "--model",
        choices=["micro", "nano", "all"],
        default="all",
        help="which variant to pack (default: all)",
    )
    args = ap.parse_args()

    selected = list(VARIANTS) if args.model == "all" else [args.model]
    variants: dict[str, dict] = {}
    for variant in selected:
        print(f"packing {variant}")
        variants[variant] = pack_variant(variant, args.out)

    # Preserve other variants already in an existing manifest when packing a subset.
    if args.model != "all" and args.manifest.exists():
        try:
            existing = json.loads(args.manifest.read_text())
            for key, value in existing.get("variants", {}).items():
                variants.setdefault(key, value)
        except json.JSONDecodeError:
            pass

    write_manifest(variants, args.manifest)
    print(f"\npacked {len(selected)} variant(s) into {args.out}")


if __name__ == "__main__":
    main()
