package com.fastt.inflect

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Minimal harness: load both graphs, synthesize whatever is in the text box, play it.
 *
 * Also doubles as the engine's `settingsActivity` (see `res/xml/tts_engine.xml`), so it is
 * the quickest way to confirm the eSpeak-ng frontend handles arbitrary text.
 */
class MainActivity : ComponentActivity() {

    private val player = AudioPlayer()
    private var tts: OnnxTts? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.status)
        val input = findViewById<EditText>(R.id.input)
        val speak = findViewById<Button>(R.id.speak)
        speak.isEnabled = false
        input.setText(DEMO_TEXT)

        lifecycleScope.launch {
            status.text = getString(R.string.loading)
            tts = OnnxTts.fromAssets(this@MainActivity)
            status.text = getString(R.string.ready)
            speak.isEnabled = true
        }

        speak.setOnClickListener {
            val engine = tts ?: return@setOnClickListener
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            speak.isEnabled = false
            lifecycleScope.launch {
                try {
                    val started = System.currentTimeMillis()
                    val waveform = engine.synthesize(text)
                    val elapsed = System.currentTimeMillis() - started
                    val seconds = waveform.size.toFloat() / OnnxTts.SAMPLE_RATE
                    status.text = getString(R.string.synthesized, seconds, elapsed)
                    player.play(waveform)
                } catch (error: Exception) {
                    // Most likely a phoneme outside the 178-symbol table; surface it rather
                    // than letting the activity die on arbitrary input.
                    status.text = getString(R.string.synthesis_failed, error.message.orEmpty())
                } finally {
                    speak.isEnabled = true
                }
            }
        }
    }

    override fun onDestroy() {
        player.close()
        tts?.close()
        super.onDestroy()
    }

    private companion object {
        const val DEMO_TEXT = "A small voice can still have something meaningful to say."
    }
}
