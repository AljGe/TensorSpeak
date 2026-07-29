package com.github.aljge.tensorspeak

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/**
 * Harness: load graphs, synthesize the text box, play it.
 *
 * Also the engine's `settingsActivity` (see `res/xml/tts_engine.xml`), so model / quality /
 * experimental runtime choices here apply to system TTS as well.
 */
class MainActivity : ComponentActivity() {

    private val player = AudioPlayer()
    private var tts: OnnxTts? = null
    private var loadingModel = false
    private var spinnerReady = false
    private var previewTts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<MaterialToolbar>(R.id.toolbar)

        val status = findViewById<TextView>(R.id.status)
        val input = findViewById<TextInputEditText>(R.id.input)
        val speak = findViewById<MaterialButton>(R.id.speak)
        val metrics = findViewById<ChipGroup>(R.id.metrics)
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

        setUpCloudVoicesSection()

        reloadEngine(status, speak) {
            spinnerReady = true
        }

        speak.setOnClickListener {
            val engine = tts ?: return@setOnClickListener
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            speak.isEnabled = false
            speak.text = getString(R.string.speaking)
            metrics.visibility = View.GONE
            metrics.removeAllViews()
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
                    status.text = getString(R.string.synthesized, seconds, elapsed)
                    showMetrics(metrics, seconds, elapsed, firstAudioMs)
                } catch (error: Exception) {
                    player.stop()
                    status.text = getString(R.string.synthesis_failed, error.message.orEmpty())
                } finally {
                    speak.text = getString(R.string.speak)
                    speak.isEnabled = tts != null && !loadingModel
                }
            }
        }

        findViewById<MaterialButton>(R.id.benchmark).setOnClickListener {
            startActivity(Intent(this, BenchmarkActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.licenses).setOnClickListener {
            startActivity(Intent(this, LicensesActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.open_tts_settings).setOnClickListener {
            openTtsSettings()
        }
    }

    private fun showMetrics(group: ChipGroup, audioSeconds: Float, totalMs: Long, ttfaMs: Long) {
        group.removeAllViews()
        group.addView(makeMetricChip(getString(R.string.metric_audio, audioSeconds)))
        group.addView(makeMetricChip(getString(R.string.metric_total, totalMs)))
        if (ttfaMs >= 0L) {
            group.addView(makeMetricChip(getString(R.string.metric_ttfa, ttfaMs)))
        }
        if (audioSeconds > 0f) {
            val rtf = totalMs / 1000.0 / audioSeconds
            group.addView(makeMetricChip(getString(R.string.metric_rtf, rtf)))
        }
        group.visibility = View.VISIBLE
    }

    private fun makeMetricChip(label: String): Chip =
        Chip(this).apply {
            text = label
            isCheckable = false
            isClickable = false
        }

    private fun openTtsSettings() {
        val intents = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                return
            }
        }
    }

    private fun setUpCloudVoicesSection() {
        val openAiKey = findViewById<TextInputEditText>(R.id.openai_api_key)
        val elevenLabsKey = findViewById<TextInputEditText>(R.id.elevenlabs_api_key)
        val elevenLabsSlots = findViewById<TextInputEditText>(R.id.elevenlabs_voice_slots)
        val customBaseUrl = findViewById<TextInputEditText>(R.id.custom_base_url)
        val customKey = findViewById<TextInputEditText>(R.id.custom_api_key)
        val customModel = findViewById<TextInputEditText>(R.id.custom_model)
        val customSlots = findViewById<TextInputEditText>(R.id.custom_voice_slots)
        val cloudStatus = findViewById<TextView>(R.id.cloud_status)
        val previewSpinner = findViewById<Spinner>(R.id.preview_voice)
        val previewButton = findViewById<MaterialButton>(R.id.preview_play)

        openAiKey.setText(CloudTtsSecrets.openAiApiKey(this))
        elevenLabsKey.setText(CloudTtsSecrets.elevenLabsApiKey(this))
        elevenLabsSlots.setText(CloudTtsPreferences.elevenLabsVoiceSlotsText(this))
        customBaseUrl.setText(CloudTtsPreferences.customBaseUrl(this))
        customKey.setText(CloudTtsSecrets.customApiKey(this))
        customModel.setText(CloudTtsPreferences.customModel(this))
        customSlots.setText(CloudTtsPreferences.customVoiceSlotsText(this))

        bindEnumSpinner(
            findViewById(R.id.openai_model),
            OpenAiModel.entries,
            { it.label },
            CloudTtsPreferences.openAiModel(this),
        ) { CloudTtsPreferences.setOpenAiModel(this, it) }

        bindEnumSpinner(
            findViewById(R.id.elevenlabs_model),
            ElevenLabsModel.entries,
            { it.label },
            CloudTtsPreferences.elevenLabsModel(this),
        ) { CloudTtsPreferences.setElevenLabsModel(this, it) }

        refreshPreviewVoices(previewSpinner)

        findViewById<MaterialButton>(R.id.save_cloud_settings).setOnClickListener {
            CloudTtsSecrets.setOpenAiApiKey(this, openAiKey.text?.toString().orEmpty())
            CloudTtsSecrets.setElevenLabsApiKey(this, elevenLabsKey.text?.toString().orEmpty())
            CloudTtsPreferences.setElevenLabsVoiceSlotsText(
                this, elevenLabsSlots.text?.toString().orEmpty(),
            )
            CloudTtsPreferences.setCustomBaseUrl(this, customBaseUrl.text?.toString().orEmpty())
            CloudTtsSecrets.setCustomApiKey(this, customKey.text?.toString().orEmpty())
            CloudTtsPreferences.setCustomModel(this, customModel.text?.toString().orEmpty())
            CloudTtsPreferences.setCustomVoiceSlotsText(this, customSlots.text?.toString().orEmpty())

            val voiceCount = refreshPreviewVoices(previewSpinner)
            cloudStatus.text = getString(R.string.cloud_status_saved, voiceCount)
        }

        previewButton.setOnClickListener {
            val voiceName = previewSpinner.selectedItem as? String
            if (voiceName == null) {
                cloudStatus.text = getString(R.string.preview_no_voices)
                return@setOnClickListener
            }
            playPreview(voiceName, cloudStatus)
        }
    }

    /** @return how many cloud voices are now configured. */
    private fun refreshPreviewVoices(spinner: Spinner): Int {
        val cloudVoiceNames = CloudVoiceCatalog.voices(this)
            .map { it.name }
            .filter { name -> ModelVariant.entries.none { it.id == name } }
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            cloudVoiceNames,
        )
        return cloudVoiceNames.size
    }

    /**
     * Speaks through this app's own registered `TextToSpeechService`, exercising the exact
     * `onLoadVoice`/`onSynthesizeText` path a real reader app would use — the practical way to
     * verify a cloud voice end-to-end, since stock Settings > Accessibility > TTS has no
     * per-voice picker.
     */
    private fun playPreview(voiceName: String, status: TextView) {
        previewTts?.shutdown()
        status.text = getString(R.string.preview_playing)
        previewTts = TextToSpeech(this, { initStatus ->
            val engine = previewTts
            if (initStatus != TextToSpeech.SUCCESS || engine == null) {
                status.text = getString(R.string.preview_failed, "engine init failed")
                return@TextToSpeech
            }
            val voice = engine.voices?.firstOrNull { it.name == voiceName }
            if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) {
                status.text = getString(R.string.preview_failed, "voice unavailable")
                return@TextToSpeech
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    runOnUiThread { status.text = readyLabel() }
                }

                @Deprecated("required override")
                override fun onError(utteranceId: String?) {
                    runOnUiThread { status.text = getString(R.string.preview_failed, "synthesis error") }
                }
            })
            engine.speak(PREVIEW_TEXT, TextToSpeech.QUEUE_FLUSH, null, "preview")
        }, packageName)
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
        speak: MaterialButton,
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
                        ExecutionBackend.entries.indexOf(ExecutionBackend.CPU),
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
        previewTts?.shutdown()
        previewTts = null
        super.onDestroy()
    }

    private companion object {
        const val DEMO_TEXT = "A small voice can still have something meaningful to say."
        const val PREVIEW_TEXT = "This is a preview of the selected cloud voice."
    }
}
