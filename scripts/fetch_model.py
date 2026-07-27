#!/usr/bin/env python3
"""Download the Inflect-Micro-v2 ONNX graphs and reference frontend into models/.

Verifies every downloaded ONNX graph against the repo's own onnx/checksums.sha256.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

from huggingface_hub import snapshot_download

REPO_ID = "owensong/Inflect-Micro-v2-ONNX"
DEST = Path(__file__).resolve().parent.parent / "models"

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


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--revision", default="main", help="git revision to pin")
    ap.add_argument("--dest", type=Path, default=DEST)
    args = ap.parse_args()

    args.dest.mkdir(parents=True, exist_ok=True)
    print(f"downloading {REPO_ID}@{args.revision} -> {args.dest}")
    snapshot_download(
        repo_id=REPO_ID,
        revision=args.revision,
        allow_patterns=ALLOW_PATTERNS,
        local_dir=str(args.dest),
    )
    verify(args.dest)

    for name in ("onnx/duration.onnx", "onnx/decode.onnx", "config.json"):
        p = args.dest / name
        print(f"  {name}: {p.stat().st_size / 1e6:.2f} MB" if p.exists() else f"  {name}: MISSING")


if __name__ == "__main__":
    main()
