package com.fastt.inflect

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Minimal harness: load both graphs, synthesize whatever is in the text box, play it.
 *
 * Also doubles as the engine's `settingsActivity` (see `res/xml/tts_engine.xml`), so it is
 * the quickest way to confirm the eSpeak-ng frontend handles arbitrary text — and to pick
 * Nano vs Micro for the system TTS service.
 */
class MainActivity : ComponentActivity() {

    private val player = AudioPlayer()
    private var tts: OnnxTts? = null
    private var loadingModel = false
    private var spinnerReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.status)
        val input = findViewById<EditText>(R.id.input)
        val speak = findViewById<Button>(R.id.speak)
        val modelSpinner = findViewById<Spinner>(R.id.model)
        speak.isEnabled = false
        input.setText(DEMO_TEXT)

        val variants = ModelVariant.entries
        modelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            variants.map { it.label },
        )
        modelSpinner.setSelection(variants.indexOf(ModelPreferences.get(this)))
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (!spinnerReady || loadingModel) return
                val selected = variants[position]
                if (tts?.variant == selected) return
                ModelPreferences.set(this@MainActivity, selected)
                loadEngine(selected, status, speak)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        loadEngine(ModelPreferences.get(this), status, speak) {
            spinnerReady = true
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
                    speak.isEnabled = tts != null && !loadingModel
                }
            }
        }

        findViewById<Button>(R.id.licenses).setOnClickListener {
            startActivity(Intent(this, LicensesActivity::class.java))
        }
    }

    private fun loadEngine(
        variant: ModelVariant,
        status: TextView,
        speak: Button,
        onDone: (() -> Unit)? = null,
    ) {
        loadingModel = true
        speak.isEnabled = false
        status.text = getString(R.string.model_loading, variant.label)
        lifecycleScope.launch {
            try {
                tts?.close()
                tts = null
                tts = OnnxTts.fromAssets(this@MainActivity, variant)
                status.text = getString(R.string.ready, variant.label)
                speak.isEnabled = true
            } catch (error: Exception) {
                status.text = getString(R.string.synthesis_failed, error.message.orEmpty())
            } finally {
                loadingModel = false
                onDone?.invoke()
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
