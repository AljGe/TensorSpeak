package com.github.aljge.tensorspeak

import android.content.Context
import android.speech.tts.Voice
import java.util.Locale

/**
 * Builds the [Voice] list `onGetVoices()` reports and resolves a chosen voice name back to
 * either the on-device engine or a cloud provider. A cloud provider only appears once it has
 * an API key (custom: a base URL) saved, so an unconfigured provider is simply absent rather
 * than present-but-broken.
 */
object CloudVoiceCatalog {
    private val LOCALE = Locale.US

    fun voices(context: Context): List<Voice> {
        val voices = mutableListOf<Voice>()
        for (variant in ModelVariant.entries) {
            voices.add(onDeviceVoice(variant))
        }
        if (CloudTtsSecrets.openAiApiKey(context).isNotEmpty()) {
            for (voice in OpenAiVoice.entries) {
                voices.add(cloudVoice("openai-${voice.id}"))
            }
        }
        if (CloudTtsSecrets.elevenLabsApiKey(context).isNotEmpty()) {
            for (slot in CloudTtsPreferences.elevenLabsVoiceSlots(context)) {
                voices.add(cloudVoice("elevenlabs-${slot.slug}"))
            }
        }
        if (CloudTtsSecrets.deepgramApiKey(context).isNotEmpty()) {
            for (voice in DeepgramVoice.entries) {
                voices.add(cloudVoice("deepgram-${voice.id}"))
            }
        }
        if (CloudTtsPreferences.customBaseUrl(context).isNotEmpty()) {
            for (slot in CloudTtsPreferences.customVoiceSlots(context)) {
                voices.add(cloudVoice("custom-${slot.slug}"))
            }
        }
        return voices
    }

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
            val slot = CloudTtsPreferences.customVoiceSlots(context)
                .firstOrNull { it.slug == slug } ?: return null
            return VoiceTarget.Cloud(
                CloudVoiceSelection.Custom(
                    baseUrl = baseUrl,
                    apiKey = CloudTtsSecrets.customApiKey(context),
                    model = CloudTtsPreferences.customModel(context),
                    voiceName = slot.id,
                    useSimpleBody = CloudTtsPreferences.customUsesSimpleBody(context),
                )
            )
        }

        return null
    }

    private fun onDeviceVoice(variant: ModelVariant): Voice =
        Voice(
            variant.id,
            LOCALE,
            Voice.QUALITY_HIGH,
            Voice.LATENCY_LOW,
            /* requiresNetworkConnection = */ false,
            emptySet(),
        )

    private fun cloudVoice(name: String): Voice =
        Voice(
            name,
            LOCALE,
            Voice.QUALITY_VERY_HIGH,
            Voice.LATENCY_NORMAL,
            /* requiresNetworkConnection = */ true,
            emptySet(),
        )

    private const val OPENAI_PREFIX = "openai-"
    private const val ELEVENLABS_PREFIX = "elevenlabs-"
    private const val DEEPGRAM_PREFIX = "deepgram-"
    private const val CUSTOM_PREFIX = "custom-"
}
