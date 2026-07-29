package com.github.aljge.tensorspeak

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Harness + engine settings host (Try / Voices / Engine tabs).
 *
 * Also the engine's `settingsActivity` (see `res/xml/tts_engine.xml`); when opened from the
 * system TTS gear, the Engine tab is selected so Chunking and quality are immediately visible.
 */
class MainActivity : FragmentActivity() {

    private val player = AudioPlayer()
    private val cloudPreview = CloudTts()
    var tts: OnnxTts? = null
        private set
    private var loadingModel = false
    var installingPack: ModelVariant? = null
        private set
    private var controlsWired = false
    private var previewGeneration = 0
    lateinit var modelPacks: ModelPackManager
        private set

    var cachedStatus: String = ""
        private set
    var canSpeak: Boolean = false
        private set

    val controlsReady: Boolean
        get() = controlsWired && !loadingModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        modelPacks = ModelPackManager(this)
        cachedStatus = getString(R.string.loading)

        ensureFragments()
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        val initialTab = resolveInitialTab(savedInstanceState)
        bottomNav.selectedItemId = initialTab
        showTab(initialTab, first = true)
        bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId, first = false)
            true
        }

        reloadEngine {
            controlsWired = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId)
    }

    override fun onResume() {
        super.onResume()
        voicesFragment()?.refreshEngineStatus()
        voicesFragment()?.refreshModelPackUi()
    }

    override fun onDestroy() {
        previewGeneration++
        player.close()
        tts?.let { EngineRepository.releaseBlocking(it) }
        tts = null
        super.onDestroy()
    }

    fun publishStatus(text: String) {
        cachedStatus = text
        tryFragment()?.setStatus(text)
    }

    fun readyLabel(): String {
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

    fun openTtsSettings() {
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

    fun broadcastVoiceDataInstalled() {
        sendBroadcast(Intent(TextToSpeech.Engine.ACTION_TTS_DATA_INSTALLED))
    }

    fun onSpeakClicked(
        text: String,
        status: TextView,
        speak: MaterialButton,
        metrics: ChipGroup,
    ) {
        val engine = tts ?: return
        if (text.isEmpty()) return
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
                val profile = ModelPreferences.latencyProfile(this@MainActivity)
                player.startStreaming()
                engine.synthesizeStreaming(
                    text = text,
                    variation = variation,
                    firstChunkLimit = profile.firstChunkLimit,
                    chunkLimit = profile.chunkLimit,
                ) { audio ->
                    if (firstAudioMs < 0L) {
                        firstAudioMs = System.currentTimeMillis() - started
                    }
                    samples += audio.size
                    player.write(audio)
                }
                val elapsed = System.currentTimeMillis() - started
                val seconds = samples.toFloat() / OnnxTts.SAMPLE_RATE
                val message = getString(R.string.synthesized, seconds, elapsed)
                publishStatus(message)
                status.text = message
                tryFragment()?.showMetrics(seconds, elapsed, firstAudioMs)
            } catch (error: Exception) {
                player.stop()
                val message = getString(R.string.synthesis_failed, error.message.orEmpty())
                publishStatus(message)
                status.text = message
            } finally {
                speak.text = getString(R.string.speak)
                setSpeakEnabled(tts != null && !loadingModel)
            }
        }
    }

    fun onDefaultVoiceSelected(voiceName: String) {
        val previous = VoicePreferences.resolvedDefaultVoiceName(this)
        if (voiceName == previous) return
        VoicePreferences.setDefaultVoice(this, voiceName)
        broadcastVoiceDataInstalled()
        val target = CloudVoiceCatalog.resolve(this, voiceName)
        if (target is VoiceTarget.OnDevice) {
            val ready = modelPacks.isInstalled(target.variant) ||
                modelPacks.hasAssetGraphs(target.variant)
            if (!ready) {
                installModelPack(target.variant) {
                    reloadEngine()
                }
            } else if (tts?.variant != target.variant) {
                reloadEngine()
            } else {
                publishStatus(readyLabel())
            }
        } else {
            publishStatus(readyLabel())
        }
    }

    fun installModelPack(variant: ModelVariant, afterInstall: (() -> Unit)? = null) {
        if (installingPack != null) return
        if (modelPacks.isInstalled(variant)) {
            afterInstall?.invoke()
            return
        }
        installingPack = variant
        voicesFragment()?.refreshModelPackUi()
        publishStatus(getString(R.string.model_pack_downloading, variant.label, 0))
        lifecycleScope.launch {
            try {
                modelPacks.install(variant) { fraction ->
                    val percent = (fraction * 100).toInt().coerceIn(0, 100)
                    val text = getString(R.string.model_pack_downloading, variant.label, percent)
                    runOnUiThread {
                        voicesFragment()?.updatePackProgress(variant, percent, text)
                        publishStatus(text)
                    }
                }
                broadcastVoiceDataInstalled()
                voicesFragment()?.refreshDefaultVoiceDropdown()
                if (afterInstall != null) {
                    afterInstall.invoke()
                } else if (
                    ModelPreferences.get(this@MainActivity) == variant ||
                    VoicePreferences.resolvedDefaultVoiceName(this@MainActivity) == variant.id
                ) {
                    reloadEngine()
                } else {
                    publishStatus(getString(R.string.model_pack_status_ready, variant.label))
                }
            } catch (error: Exception) {
                publishStatus(
                    getString(R.string.model_pack_install_failed, error.message.orEmpty()),
                )
            } finally {
                installingPack = null
                voicesFragment()?.refreshModelPackUi()
            }
        }
    }

    fun deleteModelPack(variant: ModelVariant) {
        if (installingPack != null) return
        modelPacks.delete(variant)
        if (tts?.variant == variant) {
            tts?.let { EngineRepository.releaseBlocking(it) }
            tts = null
            setSpeakEnabled(false)
        }
        voicesFragment()?.refreshModelPackUi()
        voicesFragment()?.refreshDefaultVoiceDropdown()
        val approxMb = runCatching {
            modelPacks.loadManifest().descriptor(variant).approxBytes / 1e6
        }.getOrDefault(0.0)
        publishStatus(getString(R.string.model_pack_status_missing, variant.label, approxMb))
        broadcastVoiceDataInstalled()
    }

    fun playPreview(voiceName: String, status: TextView) {
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
                        val profile = ModelPreferences.latencyProfile(this@MainActivity)
                        var started = false
                        active.synthesizeStreaming(
                            text = PREVIEW_TEXT,
                            variation = variation,
                            firstChunkLimit = profile.firstChunkLimit,
                            chunkLimit = profile.chunkLimit,
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

    fun reloadEngine(onDone: (() -> Unit)? = null) {
        loadingModel = true
        setSpeakEnabled(false)
        val variant = ModelPreferences.get(this)
        val backend = ModelPreferences.executionBackend(this)
        publishStatus(getString(R.string.model_loading, "${variant.label} / ${backend.label}"))
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
                    engineFragment()?.setBackendSelection(ExecutionBackend.CPU)
                    EngineRepository.acquire(
                        this@MainActivity,
                        variant,
                        ModelPreferences.runtimeConfig(this@MainActivity),
                    )
                }
                publishStatus(readyLabel())
                setSpeakEnabled(true)
            } catch (_: ModelPackMissingException) {
                publishStatus(getString(R.string.model_pack_missing_hint))
                setSpeakEnabled(false)
            } catch (error: Exception) {
                publishStatus(getString(R.string.synthesis_failed, error.message.orEmpty()))
            } finally {
                loadingModel = false
                onDone?.invoke()
            }
        }
    }

    private fun setSpeakEnabled(enabled: Boolean) {
        canSpeak = enabled
        tryFragment()?.setSpeakEnabled(enabled)
    }

    private fun resolveInitialTab(savedInstanceState: Bundle?): Int {
        if (savedInstanceState != null) {
            return savedInstanceState.getInt(KEY_TAB, R.id.nav_try)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_ENGINE_TAB, false)) {
            return R.id.nav_engine
        }
        val fromLauncher = intent.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        return if (fromLauncher) R.id.nav_try else R.id.nav_engine
    }

    private fun ensureFragments() {
        val fm = supportFragmentManager
        if (fm.findFragmentByTag(TryFragment.TAG) == null) {
            fm.beginTransaction()
                .add(R.id.tab_content, TryFragment(), TryFragment.TAG)
                .add(R.id.tab_content, VoicesFragment(), VoicesFragment.TAG)
                .add(R.id.tab_content, EngineFragment(), EngineFragment.TAG)
                .commitNow()
        }
    }

    private fun showTab(itemId: Int, first: Boolean) {
        val fm = supportFragmentManager
        val tryF = fm.findFragmentByTag(TryFragment.TAG)!!
        val voicesF = fm.findFragmentByTag(VoicesFragment.TAG)!!
        val engineF = fm.findFragmentByTag(EngineFragment.TAG)!!
        val selected: Fragment = when (itemId) {
            R.id.nav_voices -> voicesF
            R.id.nav_engine -> engineF
            else -> tryF
        }
        fm.beginTransaction().apply {
            listOf(tryF, voicesF, engineF).forEach { fragment ->
                if (fragment == selected) show(fragment) else hide(fragment)
            }
        }.commitNowAllowingStateLoss()
        if (!first && selected is VoicesFragment) {
            selected.refreshEngineStatus()
            selected.refreshModelPackUi()
        }
    }

    private fun tryFragment(): TryFragment? =
        supportFragmentManager.findFragmentByTag(TryFragment.TAG) as? TryFragment

    private fun voicesFragment(): VoicesFragment? =
        supportFragmentManager.findFragmentByTag(VoicesFragment.TAG) as? VoicesFragment

    private fun engineFragment(): EngineFragment? =
        supportFragmentManager.findFragmentByTag(EngineFragment.TAG) as? EngineFragment

    companion object {
        const val EXTRA_OPEN_ENGINE_TAB = "open_engine_tab"
        private const val KEY_TAB = "selected_tab"
        private const val PREVIEW_TEXT = "This is a preview of the selected voice."
    }
}
