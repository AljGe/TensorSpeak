#!/usr/bin/env bash
# Build signed per-ABI release APKs and optionally refresh the GitHub release assets.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="$(grep 'versionName = ' android/app/build.gradle.kts | sed -n 's/.*"\([^"]*\)".*/\1/p')"
APK_DIR="$ROOT/android/app/build/outputs/apk/release"
ARM64_SRC="$APK_DIR/app-arm64-v8a-release.apk"
X86_SRC="$APK_DIR/app-x86_64-release.apk"
ARM64_OUT="TensorSpeak-${VERSION}-arm64-v8a.apk"
X86_OUT="TensorSpeak-${VERSION}-x86_64.apk"
PACK_DIR="$ROOT/out/model-packs"
MICRO_ZIP="$PACK_DIR/TensorSpeak-model-micro.zip"
NANO_ZIP="$PACK_DIR/TensorSpeak-model-nano.zip"

if [[ -f "$ROOT/.signing.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.signing.env"
  set +a
fi

uv run python scripts/fetch_model.py
# Slim APK: omit ONNX graphs from assets; ship them as separate release ZIPs.
uv run python scripts/export_android_assets.py --skip-models --espeak-data
uv run python scripts/pack_model_assets.py --out "$PACK_DIR"

(
  cd android
  ./gradlew :app:assembleRelease
)

if [[ ! -f "$ARM64_SRC" || ! -f "$X86_SRC" ]]; then
  echo "Expected signed per-ABI APKs:" >&2
  echo "  $ARM64_SRC" >&2
  echo "  $X86_SRC" >&2
  echo "Configure .signing.env or android/keystore.properties (see README)." >&2
  exit 1
fi

if [[ ! -f "$MICRO_ZIP" || ! -f "$NANO_ZIP" ]]; then
  echo "Expected model packs:" >&2
  echo "  $MICRO_ZIP" >&2
  echo "  $NANO_ZIP" >&2
  exit 1
fi

cp "$ARM64_SRC" "$APK_DIR/$ARM64_OUT"
cp "$X86_SRC" "$APK_DIR/$X86_OUT"
ls -lh "$APK_DIR/$ARM64_OUT" "$APK_DIR/$X86_OUT" "$MICRO_ZIP" "$NANO_ZIP"

if [[ "${1:-}" == "--upload" ]]; then
  gh release upload "v${VERSION}" \
    "$APK_DIR/$ARM64_OUT" \
    "$APK_DIR/$X86_OUT" \
    "$MICRO_ZIP" \
    "$NANO_ZIP" \
    --repo AljGe/TensorSpeak \
    --clobber
  # Drop legacy / unsigned leftovers so Obtainium does not pick the wrong asset.
  for stale in \
    "TensorSpeak-${VERSION}-unified.apk" \
    app-release-unsigned.apk \
    app-arm64-v8a-release-unsigned.apk \
    app-x86_64-release-unsigned.apk
  do
    gh release delete-asset "v${VERSION}" "$stale" --repo AljGe/TensorSpeak --yes 2>/dev/null || true
  done
  NOTES="$(cat <<EOF
Signed per-ABI APKs for TensorSpeak ${VERSION}.

ONNX graphs are **not** bundled in the APK. Download once from this release (or from the
in-app On-device models section) before using Micro / Nano:

- \`TensorSpeak-model-micro.zip\` — Inflect Micro v2 (~38 MB)
- \`TensorSpeak-model-nano.zip\` — Inflect Nano v2 (~16 MB)

APKs:
- \`${ARM64_OUT}\` — phones (arm64-v8a); Obtainium APK filter \`arm64\`
- \`${X86_OUT}\` — x86_64 emulators / Chromebooks

Attribution and licensing:
- THIRD_PARTY_NOTICES.md
- docs/MODEL_ATTRIBUTION.md
EOF
)"
  gh release edit "v${VERSION}" --repo AljGe/TensorSpeak --notes "$NOTES"
  echo "Draft release updated; publish when ready: gh release edit v${VERSION} --draft=false"
fi
