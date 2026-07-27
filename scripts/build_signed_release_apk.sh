#!/usr/bin/env bash
# Build a signed unified release APK and optionally refresh the GitHub release asset.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="$(grep 'versionName = ' android/app/build.gradle.kts | sed -n 's/.*"\([^"]*\)".*/\1/p')"
APK_DIR="$ROOT/android/app/build/outputs/apk/release"
UNIFIED="TensorSpeak-${VERSION}-unified.apk"

if [[ -f "$ROOT/.signing.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.signing.env"
  set +a
fi

uv run python scripts/fetch_model.py
uv run python scripts/export_android_assets.py --espeak-data

(
  cd android
  ./gradlew :app:assembleRelease
)

if [[ ! -f "$APK_DIR/app-release.apk" ]]; then
  echo "Expected signed app-release.apk; only unsigned output found." >&2
  echo "Configure .signing.env or android/keystore.properties (see README)." >&2
  exit 1
fi

cp "$APK_DIR/app-release.apk" "$APK_DIR/$UNIFIED"
ls -lh "$APK_DIR/$UNIFIED"

if [[ "${1:-}" == "--upload" ]]; then
  gh release upload "v${VERSION}" "$APK_DIR/$UNIFIED" --repo AljGe/TensorSpeak --clobber
  gh release delete-asset "v${VERSION}" app-release-unsigned.apk --repo AljGe/TensorSpeak --yes 2>/dev/null || true
  NOTES="$(cat <<EOF
Unified signed APK for TensorSpeak ${VERSION}.

Includes both model variants in-app:
- Inflect Micro v2 ONNX (default)
- Inflect Nano v2 ONNX

Download \`${UNIFIED}\` (arm64-v8a + x86_64).

Attribution and licensing:
- THIRD_PARTY_NOTICES.md
- docs/MODEL_ATTRIBUTION.md
EOF
)"
  gh release edit "v${VERSION}" --repo AljGe/TensorSpeak --notes "$NOTES"
  echo "Draft release updated; publish when ready: gh release edit v${VERSION} --draft=false"
fi
