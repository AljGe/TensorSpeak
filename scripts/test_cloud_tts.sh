#!/usr/bin/env bash
# Smoke-test Deepgram + the Cloudflare custom worker using `.cloud.env`.
# Mirrors the request shapes Android builds in DeepgramTtsRequest / CustomCloudTtsRequest.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${CLOUD_ENV_FILE:-$ROOT/.cloud.env}"
OUT_DIR="${OUT_DIR:-$ROOT/out/cloud-tts}"
TEXT="${1:-Hello from TensorSpeak cloud TTS smoke test.}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE — copy .cloud.env.example and fill in credentials." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

mkdir -p "$OUT_DIR"
fail=0

# Escape a string for JSON without needing python/jq (ASCII-safe for smoke text).
json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\n'/\\n}"
  s="${s//$'\r'/\\r}"
  s="${s//$'\t'/\\t}"
  printf '%s' "$s"
}

probe_audio() {
  local label="$1" path="$2" http="$3"
  if [[ "$http" != "200" ]]; then
    echo "FAIL  $label  HTTP $http"
    if [[ -s "$path" ]]; then
      echo "      body: $(head -c 200 "$path" | tr '\n' ' ')"
    fi
    fail=1
    return
  fi
  # RIFF....WAVE or MP3 (ID3 / frame sync 0xFFEx)
  local magic4 magic2
  magic4="$(od -An -N4 -tx1 "$path" 2>/dev/null | tr -d ' \n')"
  magic2="$(od -An -N2 -tx1 "$path" 2>/dev/null | tr -d ' \n')"
  local kind=""
  if [[ "$magic4" == "52494646" ]]; then
    kind="wav"
  elif [[ "$magic4" == 494433* ]]; then
    kind="mp3(id3)"
  elif [[ "$magic2" == ff[ef]* ]]; then
    kind="mp3"
  else
    echo "FAIL  $label  HTTP 200 but not wav/mp3 (magic=$magic4)"
    echo "      body: $(head -c 200 "$path" | tr -d '\0' | tr '\n' ' ')"
    fail=1
    return
  fi
  local bytes
  bytes="$(wc -c <"$path" | tr -d ' ')"
  echo "OK    $label  HTTP 200  ${kind} ${bytes} bytes -> $path"
}

ESCAPED="$(json_escape "$TEXT")"

echo "== Deepgram =="
if [[ -z "${DEEPGRAM_API_KEY:-}" ]]; then
  echo "SKIP  DEEPGRAM_API_KEY unset in $ENV_FILE"
else
  MODEL="${DEEPGRAM_MODEL:-aura-2-orion-en}"
  DG_OUT="$OUT_DIR/deepgram.wav"
  DG_HTTP="$(
    curl -sS -o "$DG_OUT" -w '%{http_code}' \
      -X POST \
      "https://api.deepgram.com/v1/speak?model=${MODEL}&encoding=linear16&sample_rate=24000&container=wav" \
      -H "Authorization: Token ${DEEPGRAM_API_KEY}" \
      -H "Content-Type: application/json" \
      -d "{\"text\":\"${ESCAPED}\"}"
  )"
  probe_audio "deepgram ($MODEL)" "$DG_OUT" "$DG_HTTP"
fi

echo "== Cloudflare custom worker =="
if [[ -z "${CUSTOM_BASE_URL:-}" ]]; then
  echo "SKIP  CUSTOM_BASE_URL unset in $ENV_FILE"
else
  BASE="${CUSTOM_BASE_URL%/}"
  # App default: OpenAI-compatible POST {base}/audio/speech
  CF_OUT="$OUT_DIR/cloudflare-audio-speech.mp3"
  AUTH_ARGS=()
  if [[ -n "${CUSTOM_API_KEY:-}" ]]; then
    AUTH_ARGS=(-H "Authorization: Bearer ${CUSTOM_API_KEY}")
  fi
  CF_HTTP="$(
    curl -sS -o "$CF_OUT" -w '%{http_code}' \
      -X POST "${BASE}/audio/speech" \
      -H "Content-Type: application/json" \
      "${AUTH_ARGS[@]}" \
      -d "{\"input\":\"${ESCAPED}\",\"response_format\":\"wav\"}"
  )"
  probe_audio "cloudflare /audio/speech" "$CF_OUT" "$CF_HTTP"

  # Also hit the root path the worker README documents
  CF_ROOT_OUT="$OUT_DIR/cloudflare-root.mp3"
  CF_ROOT_HTTP="$(
    curl -sS -o "$CF_ROOT_OUT" -w '%{http_code}' \
      -X POST "${BASE}/" \
      -H "Content-Type: application/json" \
      "${AUTH_ARGS[@]}" \
      -d "{\"input\":\"${ESCAPED}\"}"
  )"
  probe_audio "cloudflare /" "$CF_ROOT_OUT" "$CF_ROOT_HTTP"
fi

if (( fail )); then
  echo
  echo "One or more probes failed." >&2
  exit 1
fi
echo
echo "All probed endpoints returned audio."
