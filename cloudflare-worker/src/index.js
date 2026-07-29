// Minimal TTS endpoint backed by Cloudflare Workers AI (@cf/myshell-ai/melotts).
// Accepts { "input": "..." } or { "text": "..." } (OpenAI /audio/speech-compatible
// body is also accepted; `voice`/`model`/`response_format` fields are ignored) and
// returns 24kHz mono audio/wav bytes, matching what TensorSpeak's "custom" cloud
// provider expects.

const MAX_INPUT_CHARS = 4096;
const SUPPORTED_LANGS = new Set(["en", "es", "fr", "zh", "jp", "kr"]);

function jsonError(message, status) {
  return new Response(JSON.stringify({ error: message }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
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
      const audio = await env.AI.run("@cf/myshell-ai/melotts", { text, lang });
      return new Response(audio, {
        headers: {
          "Content-Type": "audio/wav",
          "Cache-Control": "no-store",
        },
      });
    } catch (err) {
      return jsonError(err.message || "synthesis failed", 500);
    }
  },
};
