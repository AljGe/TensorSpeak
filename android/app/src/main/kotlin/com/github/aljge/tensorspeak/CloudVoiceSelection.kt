package com.github.aljge.tensorspeak

/** A resolved cloud voice, carrying everything [CloudTts] needs to make the request. */
sealed class CloudVoiceSelection {
    data class OpenAi(
        val apiKey: String,
        val model: OpenAiModel,
        val voice: OpenAiVoice,
    ) : CloudVoiceSelection()

    data class ElevenLabs(
        val apiKey: String,
        val model: ElevenLabsModel,
        val voiceId: String,
    ) : CloudVoiceSelection()

    data class Deepgram(
        val apiKey: String,
        val voice: DeepgramVoice,
    ) : CloudVoiceSelection()

    data class Custom(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val voiceName: String,
        val useSimpleBody: Boolean,
    ) : CloudVoiceSelection()
}

/** Where a resolved voice name routes to: the on-device engine or a cloud provider. */
sealed class VoiceTarget {
    data class OnDevice(val variant: ModelVariant) : VoiceTarget()
    data class Cloud(val selection: CloudVoiceSelection) : VoiceTarget()
}
