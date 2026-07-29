package com.github.aljge.tensorspeak

import android.content.Context
import android.speech.tts.Voice
import java.util.Locale

/**
 * Builds the [Voice] list `onGetVoices()` reports and resolves a chosen voice name back to
 * either the on-device engine or a cloud provider. A cloud provider only appears once it has
 * an API key (custom: a base URL) saved, so an unconfigured provider is simply absent rather
 * than present-but-broken.
 *
 * [Voice.name] stays a stable resolve id (`micro`, `openai-nova`, `deepgram-aura-2-thalia-en`).
 * A human-readable label lives in the features set as `label=…` for in-app pickers.
 */
object CloudVoiceCatalog {
    private val LOCALE = Locale.US
    const val FEATURE_LABEL_PREFIX = "label="

    fun voices(context: Context): List<Voice> {
        val packs = ModelPackManager(context)
        val voices = mutableListOf<Voice>()
        for (variant in ModelVariant.entries) {
            voices.add(onDeviceVoice(variant, packs))
        }
        if (CloudTtsSecrets.openAiApiKey(context).isNotEmpty()) {
            for (voice in OpenAiVoice.entries) {
                voices.add(cloudVoice("openai-${voice.id}", "OpenAI · ${voice.label}"))
            }
        }
        if (CloudTtsSecrets.elevenLabsApiKey(context).isNotEmpty()) {
            for (slot in CloudTtsPreferences.elevenLabsVoiceSlots(context)) {
                voices.add(cloudVoice("elevenlabs-${slot.slug}", "ElevenLabs · ${slot.label}"))
            }
        }
        if (CloudTtsSecrets.deepgramApiKey(context).isNotEmpty()) {
            for (voice in DeepgramVoice.entries) {
                voices.add(cloudVoice("deepgram-${voice.id}", "Deepgram · ${voice.label}"))
            }
        }
        if (CloudTtsPreferences.customBaseUrl(context).isNotEmpty()) {
            val slots = CloudTtsPreferences.customVoiceSlots(context)
            if (slots.isEmpty()) {
                // Base URL alone is enough for MeloTTS / simple workers — expose one voice.
                voices.add(cloudVoice(CUSTOM_DEFAULT_NAME, "Custom · Default"))
            } else {
                for (slot in slots) {
                    voices.add(cloudVoice("custom-${slot.slug}", "Custom · ${slot.label}"))
                }
            }
        }
        // Put the user's preferred default first so clients that pick voices[0] still get it.
        val preferred = VoicePreferences.resolvedDefaultVoiceName(context)
        val preferredIndex = voices.indexOfFirst { it.name == preferred }
        if (preferredIndex > 0) {
            val preferredVoice = voices.removeAt(preferredIndex)
            voices.add(0, preferredVoice)
        }
        return voices
    }

    fun displayLabel(voice: Voice): String =
        voice.features
            ?.firstOrNull { it.startsWith(FEATURE_LABEL_PREFIX) }
            ?.removePrefix(FEATURE_LABEL_PREFIX)
            ?: voice.name

    fun resolve(context: Context, name: String?): VoiceTarget? {
        if (name.isNullOrEmpty()) return null
        ModelVariant.entries.firstOrNull { it.id == name }?.let { return VoiceTarget.OnDevice(it) }

        if (name.startsWith(OPENAI_PREFIX)) {
            val apiKey = CloudTtsSecrets.openAiApiKey(context)
            if (apiKey.isEmpty()) return null
            val voice = OpenAiVoice.fromId(name.removePrefix(OPENAI_PREFIX))
            return VoiceTarget.Cloud(
                CloudVoiceSelection.OpenAi(apiKey, CloudTtsPreferences.openAiModel(context), voice)
            )
        }

        if (name.startsWith(ELEVENLABS_PREFIX)) {
            val apiKey = CloudTtsSecrets.elevenLabsApiKey(context)
            if (apiKey.isEmpty()) return null
            val slug = name.removePrefix(ELEVENLABS_PREFIX)
            val slot = CloudTtsPreferences.elevenLabsVoiceSlots(context)
                .firstOrNull { it.slug == slug } ?: return null
            return VoiceTarget.Cloud(
                CloudVoiceSelection.ElevenLabs(
                    apiKey,
                    CloudTtsPreferences.elevenLabsModel(context),
                    slot.id,
                )
            )
        }

        if (name.startsWith(DEEPGRAM_PREFIX)) {
            val apiKey = CloudTtsSecrets.deepgramApiKey(context)
            if (apiKey.isEmpty()) return null
            val voice = DeepgramVoice.fromId(name.removePrefix(DEEPGRAM_PREFIX))
            return VoiceTarget.Cloud(CloudVoiceSelection.Deepgram(apiKey, voice))
        }

        if (name.startsWith(CUSTOM_PREFIX)) {
            val baseUrl = CloudTtsPreferences.customBaseUrl(context)
            if (baseUrl.isEmpty()) return null
            val slug = name.removePrefix(CUSTOM_PREFIX)
            val slots = CloudTtsPreferences.customVoiceSlots(context)
            val slotId = when {
                slug == CUSTOM_DEFAULT_SLUG && slots.isEmpty() -> ""
                else -> slots.firstOrNull { it.slug == slug }?.id ?: return null
            }
            return VoiceTarget.Cloud(
                CloudVoiceSelection.Custom(
                    baseUrl = baseUrl,
                    apiKey = CloudTtsSecrets.customApiKey(context),
                    model = CloudTtsPreferences.customModel(context),
                    voiceName = slotId,
                    useSimpleBody = CloudTtsPreferences.customUsesSimpleBody(context),
                )
            )
        }

        return null
    }

    private fun onDeviceVoice(variant: ModelVariant, packs: ModelPackManager): Voice {
        val ready = packs.isInstalled(variant) || packs.hasAssetGraphs(variant)
        val label = onDeviceLabel(variant, ready)
        return Voice(
            variant.id,
            LOCALE,
            Voice.QUALITY_HIGH,
            Voice.LATENCY_LOW,
            /* requiresNetworkConnection = */ !ready,
            setOf("$FEATURE_LABEL_PREFIX$label"),
        )
    }

    private fun cloudVoice(name: String, label: String): Voice =
        Voice(
            name,
            LOCALE,
            Voice.QUALITY_VERY_HIGH,
            Voice.LATENCY_NORMAL,
            /* requiresNetworkConnection = */ true,
            setOf("$FEATURE_LABEL_PREFIX$label"),
        )

    private fun onDeviceLabel(variant: ModelVariant, ready: Boolean): String {
        val base = when (variant) {
            ModelVariant.MICRO -> "On-device · Micro"
            ModelVariant.NANO -> "On-device · Nano"
        }
        return if (ready) base else "$base (download required)"
    }

    private const val OPENAI_PREFIX = "openai-"
    private const val ELEVENLABS_PREFIX = "elevenlabs-"
    private const val DEEPGRAM_PREFIX = "deepgram-"
    private const val CUSTOM_PREFIX = "custom-"
    private const val CUSTOM_DEFAULT_SLUG = "default"
    const val CUSTOM_DEFAULT_NAME = "custom-default"
}
