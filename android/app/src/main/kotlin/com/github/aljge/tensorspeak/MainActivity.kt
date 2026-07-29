package com.github.aljge.tensorspeak

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Harness: load graphs, synthesize the text box, play it.
 *
 * Also the engine's `settingsActivity` (see `res/xml/tts_engine.xml`), so model / quality /
 * experimental runtime choices here apply to system TTS as well.
 */
class MainActivity : ComponentActivity() {

    private val player = AudioPlayer()
    private val cloudPreview = CloudTts()
    private var tts: OnnxTts? = null
    private var loadingModel = false
    private var spinnerReady = false
    private var defaultVoiceEntries: List<Voice> = emptyList()
    private var previewGeneration = 0

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

        setUpDefaultVoiceSpinner(status, speak)
        setUpCloudVoicesSection(status)
        refreshEngineStatus()

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

    override fun onResume() {
        super.onResume()
        refreshEngineStatus()
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

    private fun refreshEngineStatus() {
        val statusView = findViewById<TextView>(R.id.engine_status)
        val preferred = Settings.Secure.getString(contentResolver, "tts_default_synth")
        statusView.text = when {
            preferred.isNullOrEmpty() -> getString(R.string.setup_engine_status_unknown)
            preferred == packageName -> getString(R.string.setup_engine_status_preferred)
            else -> getString(R.string.setup_engine_status_other)
        }
    }

    private fun setUpDefaultVoiceSpinner(status: TextView, speak: MaterialButton) {
        val spinner = findViewById<Spinner>(R.id.default_voice)
        refreshDefaultVoiceSpinner(spinner)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (!spinnerReady || loadingModel) return
                val voice = defaultVoiceEntries.getOrNull(position) ?: return
                val previous = VoicePreferences.resolvedDefaultVoiceName(this@MainActivity)
                if (voice.name == previous) return
                VoicePreferences.setDefaultVoice(this@MainActivity, voice.name)
                sendBroadcast(Intent(TextToSpeech.Engine.ACTION_TTS_DATA_INSTALLED))
                val target = CloudVoiceCatalog.resolve(this@MainActivity, voice.name)
                if (target is VoiceTarget.OnDevice && tts?.variant != target.variant) {
                    reloadEngine(status, speak)
                } else {
                    status.text = readyLabel()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        findViewById<MaterialButton>(R.id.preview_play).setOnClickListener {
            val cloudStatus = findViewById<TextView>(R.id.cloud_status)
            val position = spinner.selectedItemPosition
            val voice = defaultVoiceEntries.getOrNull(position)
            if (voice == null) {
                cloudStatus.text = getString(R.string.preview_no_voices)
                return@setOnClickListener
            }
            playPreview(voice.name, cloudStatus)
        }
    }

    /** @return how many cloud voices are configured (excludes on-device). */
    private fun refreshDefaultVoiceSpinner(spinner: Spinner): Int {
        val voices = CloudVoiceCatalog.voices(this)
        defaultVoiceEntries = voices
        val labels = voices.map { CloudVoiceCatalog.displayLabel(it) }
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        val current = VoicePreferences.resolvedDefaultVoiceName(this)
        val index = voices.indexOfFirst { it.name == current }.coerceAtLeast(0)
        spinner.setSelection(index)
        return voices.count { ModelVariant.entries.none { variant -> variant.id == it.name } }
    }

    private fun setUpCloudVoicesSection(status: TextView) {
        val openAiKey = findViewById<TextInputEditText>(R.id.openai_api_key)
        val elevenLabsKey = findViewById<TextInputEditText>(R.id.elevenlabs_api_key)
        val elevenLabsSlots = findViewById<TextInputEditText>(R.id.elevenlabs_voice_slots)
        val deepgramKey = findViewById<TextInputEditText>(R.id.deepgram_api_key)
        val customBaseUrl = findViewById<TextInputEditText>(R.id.custom_base_url)
        val customKey = findViewById<TextInputEditText>(R.id.custom_api_key)
        val customModel = findViewById<TextInputEditText>(R.id.custom_model)
        val customSlots = findViewById<TextInputEditText>(R.id.custom_voice_slots)
        val customSimpleBody = findViewById<CheckBox>(R.id.custom_simple_body)
        val cloudStatus = findViewById<TextView>(R.id.cloud_status)
        val defaultVoiceSpinner = findViewById<Spinner>(R.id.default_voice)

        openAiKey.setText(CloudTtsSecrets.openAiApiKey(this))
        elevenLabsKey.setText(CloudTtsSecrets.elevenLabsApiKey(this))
        elevenLabsSlots.setText(CloudTtsPreferences.elevenLabsVoiceSlotsText(this))
        deepgramKey.setText(CloudTtsSecrets.deepgramApiKey(this))
        customBaseUrl.setText(CloudTtsPreferences.customBaseUrl(this))
        customKey.setText(CloudTtsSecrets.customApiKey(this))
        customModel.setText(CloudTtsPreferences.customModel(this))
        customSlots.setText(CloudTtsPreferences.customVoiceSlotsText(this))
        customSimpleBody.isChecked = CloudTtsPreferences.customUsesSimpleBody(this)

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

        findViewById<MaterialButton>(R.id.save_cloud_settings).setOnClickListener {
            CloudTtsSecrets.setOpenAiApiKey(this, openAiKey.text?.toString().orEmpty())
            CloudTtsSecrets.setElevenLabsApiKey(this, elevenLabsKey.text?.toString().orEmpty())
            CloudTtsPreferences.setElevenLabsVoiceSlotsText(
                this, elevenLabsSlots.text?.toString().orEmpty(),
            )
            CloudTtsSecrets.setDeepgramApiKey(this, deepgramKey.text?.toString().orEmpty())
            CloudTtsPreferences.setCustomBaseUrl(this, customBaseUrl.text?.toString().orEmpty())
            CloudTtsSecrets.setCustomApiKey(this, customKey.text?.toString().orEmpty())
            CloudTtsPreferences.setCustomModel(this, customModel.text?.toString().orEmpty())
            CloudTtsPreferences.setCustomVoiceSlotsText(this, customSlots.text?.toString().orEmpty())
            CloudTtsPreferences.setCustomUsesSimpleBody(this, customSimpleBody.isChecked)

            // Drop a stale default if its provider key / slot disappeared.
            VoicePreferences.resolvedDefaultVoiceName(this)

            val voiceCount = refreshDefaultVoiceSpinner(defaultVoiceSpinner)
            cloudStatus.text = getString(R.string.cloud_status_saved, voiceCount)
            status.text = readyLabel()

            sendBroadcast(Intent(TextToSpeech.Engine.ACTION_TTS_DATA_INSTALLED))
        }
    }

    /**
     * In-app preview synthesizes directly (same [CloudTts] / [OnnxTts] the system service uses)
     * and plays through [AudioPlayer]. Going through a nested `TextToSpeech` client was leaving
     * the UI stuck on "Playing…" whenever the framework never delivered onDone/onError.
     */
    private fun playPreview(voiceName: String, status: TextView) {
        val target = CloudVoiceCatalog.resolve(this, voiceName)
        if (target == null) {
            status.text = getString(R.string.preview_failed, "voice unavailable")
            return
        }
        val generation = ++previewGeneration
        player.stop()
        lifecycleScope.launch {
            try {
                when (target) {
                    is VoiceTarget.Cloud -> {
                        status.text = getString(R.string.preview_fetching)
                        val result = withContext(Dispatchers.IO) {
                            cloudPreview.synthesize(PREVIEW_TEXT, speed = 1f, target.selection)
                        }
                        if (generation != previewGeneration) return@launch
                        status.text = getString(R.string.preview_playing)
                        withContext(Dispatchers.IO) {
                            player.play(result.samples, result.sampleRate)
                        }
                    }
                    is VoiceTarget.OnDevice -> {
                        status.text = getString(R.string.preview_playing)
                        val engine = tts
                            ?: EngineRepository.acquire(
                                this@MainActivity,
                                target.variant,
                                ModelPreferences.runtimeConfig(this@MainActivity),
                            ).also { tts = it }
                        if (engine.variant != target.variant) {
                            EngineRepository.release(engine)
                            tts = EngineRepository.acquire(
                                this@MainActivity,
                                target.variant,
                                ModelPreferences.runtimeConfig(this@MainActivity),
                            )
                        }
                        val active = tts ?: error("engine unavailable")
                        val variation = ModelPreferences.variation(this@MainActivity, active.variant)
                        val firstChunkLimit = ModelPreferences.latencyProfile(this@MainActivity)
                            .firstChunkLimit
                        var started = false
                        active.synthesizeStreaming(
                            text = PREVIEW_TEXT,
                            variation = variation,
                            firstChunkLimit = firstChunkLimit,
                            shouldContinue = { generation == previewGeneration },
                        ) { audio ->
                            if (generation != previewGeneration) return@synthesizeStreaming false
                            if (!started) {
                                player.startStreaming(OnnxTts.SAMPLE_RATE)
                                started = true
                            }
                            player.write(audio)
                        }
                    }
                }
                if (generation == previewGeneration) {
                    status.text = getString(R.string.preview_done)
                }
            } catch (error: Exception) {
                if (generation != previewGeneration) return@launch
                player.stop()
                val detail = error.message?.take(160).orEmpty().ifEmpty { error.javaClass.simpleName }
                status.text = getString(R.string.preview_failed, detail)
            }
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

    private fun readyLabel(): String {
        val defaultName = VoicePreferences.resolvedDefaultVoiceName(this)
        val voiceLabel = CloudVoiceCatalog.voices(this)
            .firstOrNull { it.name == defaultName }
            ?.let { CloudVoiceCatalog.displayLabel(it) }
            ?: ModelPreferences.get(this).label
        return getString(
            R.string.ready_detail,
            voiceLabel,
            ModelPreferences.executionBackend(this).label,
            ModelPreferences.latencyProfile(this).label,
        )
    }

    override fun onDestroy() {
        previewGeneration++
        player.close()
        tts?.let { EngineRepository.releaseBlocking(it) }
        tts = null
        super.onDestroy()
    }

    private companion object {
        const val DEMO_TEXT = "A small voice can still have something meaningful to say."
        const val PREVIEW_TEXT = "This is a preview of the selected voice."
    }
}
