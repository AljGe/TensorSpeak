package com.fastt.inflect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * Answers `android.speech.tts.engine.CHECK_TTS_DATA`.
 *
 * The engine picker in Settings will not offer an engine that does not respond to this, even
 * when the voice data ships inside the APK - which ours does, so the answer is always PASS.
 */
class CheckVoiceDataActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val available = arrayListOf(VOICE)
        val result = Intent().apply {
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf())
        }
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        finish()
    }

    private companion object {
        const val VOICE = "eng-USA"
    }
}
