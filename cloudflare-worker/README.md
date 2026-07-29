# tensorspeak-tts Worker

A tiny Cloudflare Worker that exposes `@cf/myshell-ai/melotts` (Workers AI) as an
HTTP TTS endpoint, for use as TensorSpeak's "custom" cloud voice provider.

`@cf/myshell-ai/melotts` costs ~18.63 Neurons per audio-minute. The free Workers AI
tier is 10,000 Neurons/day, so this is roughly **9 hours of speech per day for
free** — the cheapest option in the Workers AI catalog by a wide margin (Deepgram
Aura 2 burns the same daily budget in a few minutes).

## Deploy

Requires a Cloudflare account and Node.js. Run from this directory:

```bash
npx wrangler login
npx wrangler deploy
```

Wrangler prints your endpoint URL, e.g. `https://tensorspeak-tts.<subdomain>.workers.dev`.

### Optional: restrict access

The worker is public once deployed — anyone with the URL can spend your Neurons.
To gate it behind a shared secret:

```bash
npx wrangler secret put SHARED_SECRET
```

Requests then need `Authorization: Bearer <that secret>`. In TensorSpeak, put the
same value in the custom provider's API key field.

## Test

```bash
curl -X POST https://tensorspeak-tts.<subdomain>.workers.dev \
  -H "Content-Type: application/json" \
  -d '{"input":"Testing my free Cloudflare Workers AI speech engine."}' \
  --output test_audio.mp3
```

Add `-H "Authorization: Bearer <secret>"` if you set `SHARED_SECRET`.

Or use the repo smoke test (also covers Deepgram): copy [`.cloud.env.example`](../.cloud.env.example)
to `.cloud.env`, set `CUSTOM_BASE_URL` (and optionally `DEEPGRAM_API_KEY`), then:

```bash
./scripts/test_cloud_tts.sh
```

MeloTTS returns **MP3**; TensorSpeak's custom provider accepts that via `AudioBlobDecoder`.

## Wiring into TensorSpeak

In the app's "Cloud voices" settings section, under the custom provider:
- **Base URL**: the `workers.dev` URL above.
- **API key**: the `SHARED_SECRET` value, if set — otherwise leave blank.
- Leave model/voice fields blank; this worker ignores them.

Request/response shape: `POST {input: string, lang?: string}` -> `audio/mpeg` (MP3) bytes.
`lang` defaults to `"en"`; supported values are `en`, `es`, `fr`, `zh`, `jp`, `kr`.
