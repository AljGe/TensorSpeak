package com.github.aljge.tensorspeak

import android.os.Bundle
import android.speech.tts.Voice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class VoicesFragment : Fragment() {
    private var cloudExpanded = false
    private var defaultVoiceField: AutoCompleteTextView? = null
    private var voiceEntries: List<Voice> = emptyList()
    private var suppressVoiceCallback = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_voices, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val host = requireActivity() as MainActivity

        view.findViewById<MaterialButton>(R.id.open_tts_settings).setOnClickListener {
            host.openTtsSettings()
        }

        defaultVoiceField = view.findViewById(R.id.default_voice)
        refreshDefaultVoiceDropdown()
        defaultVoiceField?.setOnItemClickListener { _, _, position, _ ->
            if (suppressVoiceCallback || !host.controlsReady) return@setOnItemClickListener
            val voice = voiceEntries.getOrNull(position) ?: return@setOnItemClickListener
            host.onDefaultVoiceSelected(voice.name)
        }

        view.findViewById<MaterialButton>(R.id.preview_play).setOnClickListener {
            val cloudStatus = view.findViewById<TextView>(R.id.cloud_status)
            val text = defaultVoiceField?.text?.toString().orEmpty()
            val voice = voiceEntries.firstOrNull {
                CloudVoiceCatalog.displayLabel(it) == text
            }
            if (voice == null) {
                cloudStatus.text = getString(R.string.preview_no_voices)
                return@setOnClickListener
            }
            host.playPreview(voice.name, cloudStatus)
        }

        view.findViewById<MaterialButton>(R.id.model_pack_micro_install).setOnClickListener {
            host.installModelPack(ModelVariant.MICRO)
        }
        view.findViewById<MaterialButton>(R.id.model_pack_nano_install).setOnClickListener {
            host.installModelPack(ModelVariant.NANO)
        }
        view.findViewById<MaterialButton>(R.id.model_pack_micro_delete).setOnClickListener {
            host.deleteModelPack(ModelVariant.MICRO)
        }
        view.findViewById<MaterialButton>(R.id.model_pack_nano_delete).setOnClickListener {
            host.deleteModelPack(ModelVariant.NANO)
        }

        setUpCloudSection(view, host, savedInstanceState)
        refreshEngineStatus()
        refreshModelPackUi()
    }

    override fun onResume() {
        super.onResume()
        refreshEngineStatus()
        refreshModelPackUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_CLOUD, cloudExpanded)
    }

    fun refreshEngineStatus() {
        val statusView = view?.findViewById<TextView>(R.id.engine_status) ?: return
        val preferred = android.provider.Settings.Secure.getString(
            requireContext().contentResolver,
            "tts_default_synth",
        )
        statusView.text = when {
            preferred.isNullOrEmpty() -> getString(R.string.setup_engine_status_unknown)
            preferred == requireContext().packageName ->
                getString(R.string.setup_engine_status_preferred)
            else -> getString(R.string.setup_engine_status_other)
        }
    }

    fun refreshModelPackUi() {
        val host = activity as? MainActivity ?: return
        refreshModelPackRow(
            host,
            ModelVariant.MICRO,
            R.id.model_pack_micro_status,
            R.id.model_pack_micro_progress,
            R.id.model_pack_micro_install,
            R.id.model_pack_micro_delete,
        )
        refreshModelPackRow(
            host,
            ModelVariant.NANO,
            R.id.model_pack_nano_status,
            R.id.model_pack_nano_progress,
            R.id.model_pack_nano_install,
            R.id.model_pack_nano_delete,
        )
    }

    fun refreshDefaultVoiceDropdown(): Int {
        val field = defaultVoiceField ?: return 0
        val host = requireActivity() as MainActivity
        val voices = CloudVoiceCatalog.voices(host)
        voiceEntries = voices
        val labels = voices.map { CloudVoiceCatalog.displayLabel(it) }
        suppressVoiceCallback = true
        field.setAdapter(
            ArrayAdapter(
                host,
                android.R.layout.simple_dropdown_item_1line,
                labels,
            ),
        )
        val current = VoicePreferences.resolvedDefaultVoiceName(host)
        val selected = voices.firstOrNull { it.name == current } ?: voices.firstOrNull()
        if (selected != null) {
            field.setText(CloudVoiceCatalog.displayLabel(selected), false)
        }
        suppressVoiceCallback = false
        return voices.count { ModelVariant.entries.none { variant -> variant.id == it.name } }
    }

    fun updatePackProgress(variant: ModelVariant, percent: Int, statusText: String) {
        val statusId = if (variant == ModelVariant.MICRO) {
            R.id.model_pack_micro_status
        } else {
            R.id.model_pack_nano_status
        }
        val progressId = if (variant == ModelVariant.MICRO) {
            R.id.model_pack_micro_progress
        } else {
            R.id.model_pack_nano_progress
        }
        view?.findViewById<TextView>(statusId)?.text = statusText
        view?.findViewById<ProgressBar>(progressId)?.apply {
            progress = percent
            visibility = View.VISIBLE
        }
    }

    private fun refreshModelPackRow(
        host: MainActivity,
        variant: ModelVariant,
        statusId: Int,
        progressId: Int,
        installId: Int,
        deleteId: Int,
    ) {
        val root = view ?: return
        val statusView = root.findViewById<TextView>(statusId)
        val progress = root.findViewById<ProgressBar>(progressId)
        val install = root.findViewById<MaterialButton>(installId)
        val delete = root.findViewById<MaterialButton>(deleteId)
        val installing = host.installingPack == variant
        val installed = host.modelPacks.isInstalled(variant)
        val assets = host.modelPacks.hasAssetGraphs(variant)
        val approxMb = runCatching {
            host.modelPacks.loadManifest().descriptor(variant).approxBytes / 1e6
        }.getOrDefault(0.0)

        if (!installing) {
            statusView.text = when {
                installed -> getString(R.string.model_pack_status_ready, variant.label)
                assets -> getString(R.string.model_pack_status_assets, variant.label)
                else -> getString(R.string.model_pack_status_missing, variant.label, approxMb)
            }
        }
        progress.visibility = if (installing) View.VISIBLE else View.GONE
        install.isEnabled = !installing && host.installingPack == null && !installed
        delete.isEnabled = !installing && host.installingPack == null && installed
    }

    private fun setUpCloudSection(
        view: View,
        host: MainActivity,
        savedInstanceState: Bundle?,
    ) {
        val openAiKey = view.findViewById<TextInputEditText>(R.id.openai_api_key)
        val elevenLabsKey = view.findViewById<TextInputEditText>(R.id.elevenlabs_api_key)
        val elevenLabsSlots = view.findViewById<TextInputEditText>(R.id.elevenlabs_voice_slots)
        val deepgramKey = view.findViewById<TextInputEditText>(R.id.deepgram_api_key)
        val customBaseUrl = view.findViewById<TextInputEditText>(R.id.custom_base_url)
        val customKey = view.findViewById<TextInputEditText>(R.id.custom_api_key)
        val customModel = view.findViewById<TextInputEditText>(R.id.custom_model)
        val customSlots = view.findViewById<TextInputEditText>(R.id.custom_voice_slots)
        val customSimpleBody = view.findViewById<CheckBox>(R.id.custom_simple_body)
        val cloudStatus = view.findViewById<TextView>(R.id.cloud_status)
        val cloudBody = view.findViewById<View>(R.id.cloud_voices_body)
        val cloudToggle = view.findViewById<MaterialButton>(R.id.cloud_voices_toggle)

        openAiKey.setText(CloudTtsSecrets.openAiApiKey(host))
        elevenLabsKey.setText(CloudTtsSecrets.elevenLabsApiKey(host))
        elevenLabsSlots.setText(CloudTtsPreferences.elevenLabsVoiceSlotsText(host))
        deepgramKey.setText(CloudTtsSecrets.deepgramApiKey(host))
        customBaseUrl.setText(CloudTtsPreferences.customBaseUrl(host))
        customKey.setText(CloudTtsSecrets.customApiKey(host))
        customModel.setText(CloudTtsPreferences.customModel(host))
        customSlots.setText(CloudTtsPreferences.customVoiceSlotsText(host))
        customSimpleBody.isChecked = CloudTtsPreferences.customUsesSimpleBody(host)

        EnumDropdown.bind(
            view.findViewById(R.id.openai_model),
            OpenAiModel.entries,
            { it.label },
            CloudTtsPreferences.openAiModel(host),
        ) { CloudTtsPreferences.setOpenAiModel(host, it) }

        EnumDropdown.bind(
            view.findViewById(R.id.elevenlabs_model),
            ElevenLabsModel.entries,
            { it.label },
            CloudTtsPreferences.elevenLabsModel(host),
        ) { CloudTtsPreferences.setElevenLabsModel(host, it) }

        cloudExpanded = savedInstanceState?.getBoolean(KEY_CLOUD)
            ?: hasConfiguredCloud(host)
        applyCloudExpanded(cloudBody, cloudToggle)
        cloudToggle.setOnClickListener {
            cloudExpanded = !cloudExpanded
            applyCloudExpanded(cloudBody, cloudToggle)
        }

        view.findViewById<MaterialButton>(R.id.save_cloud_settings).setOnClickListener {
            CloudTtsSecrets.setOpenAiApiKey(host, openAiKey.text?.toString().orEmpty())
            CloudTtsSecrets.setElevenLabsApiKey(host, elevenLabsKey.text?.toString().orEmpty())
            CloudTtsPreferences.setElevenLabsVoiceSlotsText(
                host,
                elevenLabsSlots.text?.toString().orEmpty(),
            )
            CloudTtsSecrets.setDeepgramApiKey(host, deepgramKey.text?.toString().orEmpty())
            CloudTtsPreferences.setCustomBaseUrl(host, customBaseUrl.text?.toString().orEmpty())
            CloudTtsSecrets.setCustomApiKey(host, customKey.text?.toString().orEmpty())
            CloudTtsPreferences.setCustomModel(host, customModel.text?.toString().orEmpty())
            CloudTtsPreferences.setCustomVoiceSlotsText(
                host,
                customSlots.text?.toString().orEmpty(),
            )
            CloudTtsPreferences.setCustomUsesSimpleBody(host, customSimpleBody.isChecked)

            VoicePreferences.resolvedDefaultVoiceName(host)
            val voiceCount = refreshDefaultVoiceDropdown()
            cloudStatus.text = getString(R.string.cloud_status_saved, voiceCount)
            host.publishStatus(host.readyLabel())
            host.broadcastVoiceDataInstalled()
        }
    }

    private fun applyCloudExpanded(body: View, toggle: MaterialButton) {
        body.visibility = if (cloudExpanded) View.VISIBLE else View.GONE
        toggle.text = getString(
            if (cloudExpanded) R.string.cloud_voices_collapse else R.string.cloud_voices_expand,
        )
        toggle.setIconResource(
            if (cloudExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
        )
    }

    private fun hasConfiguredCloud(host: MainActivity): Boolean =
        CloudTtsSecrets.openAiApiKey(host).isNotBlank() ||
            CloudTtsSecrets.elevenLabsApiKey(host).isNotBlank() ||
            CloudTtsSecrets.deepgramApiKey(host).isNotBlank() ||
            CloudTtsSecrets.customApiKey(host).isNotBlank() ||
            CloudTtsPreferences.customBaseUrl(host).isNotBlank()

    companion object {
        const val TAG = "voices"
        private const val KEY_CLOUD = "cloud_expanded"
    }
}
