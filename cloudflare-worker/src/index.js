// Minimal TTS endpoint backed by Cloudflare Workers AI (@cf/myshell-ai/melotts).
// Accepts { "input": "..." } or { "text": "..." } (OpenAI /audio/speech-compatible
// body is also accepted; `voice`/`model`/`response_format` fields are ignored) and
// returns audio bytes (WAV from MeloTTS), matching what TensorSpeak's "custom" cloud
// provider expects (AudioBlobDecoder accepts wav or mp3).

const MAX_INPUT_CHARS = 4096;
const SUPPORTED_LANGS = new Set(["en", "es", "fr", "zh", "jp", "kr"]);

function jsonError(message, status) {
  return new Response(JSON.stringify({ error: message }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/** Normalize Workers AI melotts output to raw audio bytes. */
function audioBytes(result) {
  if (result == null) return null;
  if (typeof result === "string") {
    // base64 mp3
    const bin = atob(result);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
  }
  if (result instanceof ArrayBuffer) return new Uint8Array(result);
  if (ArrayBuffer.isView(result)) {
    return new Uint8Array(result.buffer, result.byteOffset, result.byteLength);
  }
  if (typeof result === "object" && typeof result.audio === "string") {
    return audioBytes(result.audio);
  }
  return null;
}

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return jsonError("send a POST request with a JSON body: {\"input\": \"text to speak\"}", 405);
    }

    // Optional shared-secret auth: set with `wrangler secret put SHARED_SECRET`.
    // Left unset, the worker is open to anyone with the URL.
    if (env.SHARED_SECRET) {
      const auth = request.headers.get("Authorization") || "";
      if (auth !== `Bearer ${env.SHARED_SECRET}`) {
        return jsonError("unauthorized", 401);
      }
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return jsonError("request body must be JSON", 400);
    }

    const text = typeof body.input === "string" ? body.input : body.text;
    if (typeof text !== "string" || text.trim().length === 0) {
      return jsonError("missing 'input' (or 'text') field in JSON body", 400);
    }
    if (text.length > MAX_INPUT_CHARS) {
      return jsonError(`'input' exceeds ${MAX_INPUT_CHARS} characters`, 400);
    }

    const lang = SUPPORTED_LANGS.has(body.lang) ? body.lang : "en";

    try {
      // Workers AI schema uses `prompt` (not `text`) for @cf/myshell-ai/melotts.
      // The model intermittently returns 3043; retry with backoff — five attempts usually clear it.
      let result;
      let lastErr;
      for (let attempt = 0; attempt < 5; attempt++) {
        try {
          result = await env.AI.run("@cf/myshell-ai/melotts", { prompt: text, lang });
          lastErr = null;
          break;
        } catch (err) {
          lastErr = err;
          const msg = String(err && err.message ? err.message : err);
          const transient = msg.includes("3043") || msg.includes("Internal server error");
          if (!transient || attempt === 4) break;
          await new Promise((r) => setTimeout(r, 250 * (attempt + 1)));
        }
      }
      if (lastErr) throw lastErr;

      const bytes = audioBytes(result);
      if (!bytes || bytes.byteLength === 0) {
        return jsonError("empty audio from Workers AI", 500);
      }
      // MeloTTS currently returns 44.1 kHz PCM WAV (docs still mention mp3).
      const isWav = bytes.length >= 4 &&
        bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[2] === 0x46 && bytes[3] === 0x46;
      return new Response(bytes, {
        headers: {
          "Content-Type": isWav ? "audio/wav" : "audio/mpeg",
          "Cache-Control": "no-store",
        },
      });
    } catch (err) {
      return jsonError(err.message || "synthesis failed", 500);
    }
  },
};
