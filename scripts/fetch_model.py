#!/usr/bin/env python3
"""Download Inflect-Micro / Inflect-Nano ONNX graphs into models/<variant>/.

Verifies every downloaded ONNX graph against each repo's own onnx/checksums.sha256.
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
from pathlib import Path

from huggingface_hub import snapshot_download

MODELS_DIR = Path(__file__).resolve().parent.parent / "models"

VARIANTS = {
    "micro": "owensong/Inflect-Micro-v2-ONNX",
    "nano": "owensong/Inflect-Nano-v2-ONNX",
}

# Graphs + config + the upstream reference implementation we check parity against.
ALLOW_PATTERNS = [
    "onnx/*.onnx",
    "onnx/checksums.sha256",
    "onnx/inference_onnx.py",
    "onnx/requirements.txt",
    "onnx/README.md",
    "onnx/SOURCE.json",
    "onnx/parity_report.json",
    "config.json",
    # Upstream frontend, imported directly by src/inflect_sandbox/frontend.py for phoneme parity
    "inflect_vits_frontend.py",
    "inflect_nano_v2_frontend.py",
    "runtime/text/*",
    "LICENSE",
]


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def verify(root: Path) -> None:
    """Check downloaded files against onnx/checksums.sha256; exit non-zero on mismatch."""
    manifest = root / "onnx" / "checksums.sha256"
    if not manifest.exists():
        sys.exit(f"missing checksum manifest: {manifest}")

    failures = []
    checked = 0
    for line in manifest.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        expected, _, name = line.partition("  ")
        name = name.strip().lstrip("*")
        # Manifest paths are relative to the repo root or to onnx/; try both.
        candidates = [root / name, root / "onnx" / Path(name).name]
        target = next((c for c in candidates if c.exists()), None)
        if target is None:
            continue  # file not in our allow-list
        actual = sha256(target)
        checked += 1
        if actual != expected:
            failures.append(f"{target}: expected {expected}, got {actual}")

    if failures:
        sys.exit("CHECKSUM MISMATCH:\n  " + "\n  ".join(failures))
    if checked == 0:
        sys.exit("no downloaded files matched the checksum manifest - refusing to continue")
    print(f"checksums ok ({checked} files verified)")


def migrate_flat_layout(models_dir: Path) -> None:
    """One-time: move a pre-variant flat models/ tree into models/micro/."""
    flat_marker = models_dir / "onnx" / "duration.onnx"
    micro_marker = models_dir / "micro" / "onnx" / "duration.onnx"
    if not flat_marker.exists() or micro_marker.exists():
        return

    micro = models_dir / "micro"
    micro.mkdir(parents=True, exist_ok=True)
    print(f"migrating flat models/ -> {micro}/")
    for name in (
        "onnx",
        "runtime",
        "config.json",
        "inflect_vits_frontend.py",
        "inflect_nano_v2_frontend.py",
        "LICENSE",
    ):
        source = models_dir / name
        if source.exists():
            shutil.move(str(source), str(micro / name))


def fetch_variant(variant: str, revision: str, models_dir: Path) -> None:
    repo_id = VARIANTS[variant]
    dest = models_dir / variant
    dest.mkdir(parents=True, exist_ok=True)
    print(f"downloading {repo_id}@{revision} -> {dest}")
    snapshot_download(
        repo_id=repo_id,
        revision=revision,
        allow_patterns=ALLOW_PATTERNS,
        local_dir=str(dest),
    )
    verify(dest)

    for name in ("onnx/duration.onnx", "onnx/decode.onnx", "config.json"):
        p = dest / name
        print(f"  {name}: {p.stat().st_size / 1e6:.2f} MB" if p.exists() else f"  {name}: MISSING")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--model",
        choices=["micro", "nano", "all"],
        default="all",
        help="which variant to download (default: all)",
    )
    ap.add_argument("--revision", default="main", help="git revision to pin")
    ap.add_argument("--dest", type=Path, default=MODELS_DIR, help="models/ parent directory")
    args = ap.parse_args()

    args.dest.mkdir(parents=True, exist_ok=True)
    migrate_flat_layout(args.dest)

    selected = list(VARIANTS) if args.model == "all" else [args.model]
    for variant in selected:
        fetch_variant(variant, args.revision, args.dest)


if __name__ == "__main__":
    main()
