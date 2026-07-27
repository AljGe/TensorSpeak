package com.fastt.inflect

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Minimal harness: load both graphs, synthesize one fixture sentence, play it.
 * Free-form text arrives in Stage 3 with the eSpeak-ng JNI frontend.
 */
class MainActivity : ComponentActivity() {

    private val player = AudioPlayer()
    private var tts: OnnxTts? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.status)
        val speak = findViewById<Button>(R.id.speak)
        speak.isEnabled = false

        lifecycleScope.launch {
            status.text = getString(R.string.loading)
            tts = OnnxTts.fromAssets(this@MainActivity)
            status.text = getString(R.string.ready)
            speak.isEnabled = true
        }

        speak.setOnClickListener {
            val engine = tts ?: return@setOnClickListener
            speak.isEnabled = false
            lifecycleScope.launch {
                val started = System.currentTimeMillis()
                val waveform = engine.synthesize(DEMO_TEXT)
                val elapsed = System.currentTimeMillis() - started
                val seconds = waveform.size.toFloat() / OnnxTts.SAMPLE_RATE
                status.text = getString(R.string.synthesized, seconds, elapsed)
                player.play(waveform)
                speak.isEnabled = true
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
