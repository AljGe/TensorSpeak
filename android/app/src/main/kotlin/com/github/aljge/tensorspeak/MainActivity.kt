package com.github.aljge.tensorspeak

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
 * Minimal harness: load graphs, synthesize the text box, play it.
 *
 * Also the engine's `settingsActivity` (see `res/xml/tts_engine.xml`), so model / quality /
 * experimental runtime choices here apply to system TTS as well.
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
        speak.isEnabled = false
        input.setText(DEMO_TEXT)

        bindEnumSpinner(
            findViewById(R.id.model),
            ModelVariant.entries,
            { it.label },
            ModelPreferences.get(this),
        ) { selected ->
            if (tts?.variant == selected) return@bindEnumSpinner
            ModelPreferences.set(this, selected)
            reloadEngine(status, speak)
        }

        bindEnumSpinner(
            findViewById(R.id.quality),
            QualityProfile.entries,
            { it.label },
            ModelPreferences.qualityProfile(this),
        ) { selected ->
            ModelPreferences.setQualityProfile(this, selected)
        }

        bindEnumSpinner(
            findViewById(R.id.backend),
            ExecutionBackend.entries,
            { it.label },
            ModelPreferences.executionBackend(this),
        ) { selected ->
            if (ModelPreferences.executionBackend(this) == selected &&
                tts?.runtimeConfig?.provider == selected.provider
            ) {
                return@bindEnumSpinner
            }
            ModelPreferences.setExecutionBackend(this, selected)
            reloadEngine(status, speak)
        }

        bindEnumSpinner(
            findViewById(R.id.latency),
            LatencyProfile.entries,
            { it.label },
            ModelPreferences.latencyProfile(this),
        ) { selected ->
            ModelPreferences.setLatencyProfile(this, selected)
            // No session reload: first-chunk budget is read per utterance.
            status.text = readyLabel()
        }

        bindEnumSpinner(
            findViewById(R.id.threads),
            ThreadProfile.entries,
            { it.label },
            ModelPreferences.threadProfile(this),
        ) { selected ->
            if (ModelPreferences.threadProfile(this) == selected &&
                tts?.runtimeConfig?.intraOpThreads == selected.resolve()
            ) {
                return@bindEnumSpinner
            }
            ModelPreferences.setThreadProfile(this, selected)
            reloadEngine(status, speak)
        }

        reloadEngine(status, speak) {
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
                    var firstAudioMs = -1L
                    var samples = 0
                    val variation = ModelPreferences.variation(this@MainActivity, engine.variant)
                    val firstChunkLimit = ModelPreferences.latencyProfile(this@MainActivity)
                        .firstChunkLimit
                    player.startStreaming()
                    engine.synthesizeStreaming(
                        text = text,
                        variation = variation,
                        firstChunkLimit = firstChunkLimit,
                    ) { audio ->
                        if (firstAudioMs < 0L) {
                            firstAudioMs = System.currentTimeMillis() - started
                        }
                        samples += audio.size
                        player.write(audio)
                    }
                    val elapsed = System.currentTimeMillis() - started
                    val seconds = samples.toFloat() / OnnxTts.SAMPLE_RATE
                    status.text = getString(R.string.synthesized, seconds, elapsed) +
                        if (firstAudioMs >= 0L) " (ttfa ${firstAudioMs} ms)" else ""
                } catch (error: Exception) {
                    player.stop()
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

    private fun <T> bindEnumSpinner(
        spinner: Spinner,
        values: List<T>,
        label: (T) -> String,
        current: T,
        onSelected: (T) -> Unit,
    ) {
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            values.map(label),
        )
        spinner.setSelection(values.indexOf(current).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (!spinnerReady || loadingModel) return
                onSelected(values[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun reloadEngine(
        status: TextView,
        speak: Button,
        onDone: (() -> Unit)? = null,
    ) {
        loadingModel = true
        speak.isEnabled = false
        val variant = ModelPreferences.get(this)
        val backend = ModelPreferences.executionBackend(this)
        status.text = getString(R.string.model_loading, "${variant.label} / ${backend.label}")
        lifecycleScope.launch {
            try {
                tts?.let { EngineRepository.release(it) }
                tts = null
                val config = ModelPreferences.runtimeConfig(this@MainActivity)
                tts = try {
                    EngineRepository.acquire(this@MainActivity, variant, config)
                } catch (error: Exception) {
                    if (config.provider == OnnxTts.Provider.CPU) throw error
                    ModelPreferences.setExecutionBackend(this@MainActivity, ExecutionBackend.CPU)
                    findViewById<Spinner>(R.id.backend).setSelection(
                        ExecutionBackend.entries.indexOf(ExecutionBackend.CPU)
                    )
                    EngineRepository.acquire(
                        this@MainActivity,
                        variant,
                        ModelPreferences.runtimeConfig(this@MainActivity),
                    )
                }
                status.text = readyLabel()
                speak.isEnabled = true
            } catch (error: Exception) {
                status.text = getString(R.string.synthesis_failed, error.message.orEmpty())
            } finally {
                loadingModel = false
                onDone?.invoke()
            }
        }
    }

    private fun readyLabel(): String =
        getString(
            R.string.ready_detail,
            ModelPreferences.get(this).label,
            ModelPreferences.executionBackend(this).label,
            ModelPreferences.latencyProfile(this).label,
        )

    override fun onDestroy() {
        player.close()
        tts?.let { EngineRepository.releaseBlocking(it) }
        tts = null
        super.onDestroy()
    }

    private companion object {
        const val DEMO_TEXT = "A small voice can still have something meaningful to say."
    }
}
