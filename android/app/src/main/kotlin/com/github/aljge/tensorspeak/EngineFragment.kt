package com.github.aljge.tensorspeak

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

class EngineFragment : Fragment() {
    private var backendField: AutoCompleteTextView? = null
    private var advancedExpanded = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_engine, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val host = requireActivity() as MainActivity
        val quality = view.findViewById<AutoCompleteTextView>(R.id.quality)
        val latency = view.findViewById<AutoCompleteTextView>(R.id.latency)
        val backend = view.findViewById<AutoCompleteTextView>(R.id.backend)
        val threads = view.findViewById<AutoCompleteTextView>(R.id.threads)
        backendField = backend

        EnumDropdown.bind(
            quality,
            QualityProfile.entries,
            { it.label },
            ModelPreferences.qualityProfile(host),
            enabled = { host.controlsReady },
        ) { selected ->
            ModelPreferences.setQualityProfile(host, selected)
        }

        EnumDropdown.bind(
            latency,
            LatencyProfile.entries,
            { it.label },
            ModelPreferences.latencyProfile(host),
            enabled = { host.controlsReady },
        ) { selected ->
            ModelPreferences.setLatencyProfile(host, selected)
            host.publishStatus(host.readyLabel())
        }

        EnumDropdown.bind(
            backend,
            ExecutionBackend.entries,
            { it.label },
            ModelPreferences.executionBackend(host),
            enabled = { host.controlsReady },
        ) { selected ->
            if (ModelPreferences.executionBackend(host) == selected &&
                host.tts?.runtimeConfig?.provider == selected.provider
            ) {
                return@bind
            }
            ModelPreferences.setExecutionBackend(host, selected)
            host.reloadEngine()
        }

        EnumDropdown.bind(
            threads,
            ThreadProfile.entries,
            { it.label },
            ModelPreferences.threadProfile(host),
            enabled = { host.controlsReady },
        ) { selected ->
            if (ModelPreferences.threadProfile(host) == selected &&
                host.tts?.runtimeConfig?.intraOpThreads == selected.resolve()
            ) {
                return@bind
            }
            ModelPreferences.setThreadProfile(host, selected)
            host.reloadEngine()
        }

        val advancedBody = view.findViewById<View>(R.id.advanced_runtime_body)
        val advancedToggle = view.findViewById<MaterialButton>(R.id.advanced_runtime_toggle)
        advancedExpanded = savedInstanceState?.getBoolean(KEY_ADVANCED) == true
        applyAdvanced(advancedBody, advancedToggle)
        advancedToggle.setOnClickListener {
            advancedExpanded = !advancedExpanded
            applyAdvanced(advancedBody, advancedToggle)
        }

        view.findViewById<MaterialButton>(R.id.benchmark).setOnClickListener {
            startActivity(Intent(host, BenchmarkActivity::class.java))
        }
        view.findViewById<MaterialButton>(R.id.licenses).setOnClickListener {
            startActivity(Intent(host, LicensesActivity::class.java))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ADVANCED, advancedExpanded)
    }

    fun setBackendSelection(backend: ExecutionBackend) {
        val field = backendField ?: return
        EnumDropdown.setSelection(
            field,
            ExecutionBackend.entries,
            { it.label },
            backend,
        )
    }

    private fun applyAdvanced(body: View, toggle: MaterialButton) {
        body.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
        toggle.text = getString(
            if (advancedExpanded) {
                R.string.advanced_runtime_collapse
            } else {
                R.string.advanced_runtime_expand
            },
        )
        toggle.setIconResource(
            if (advancedExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
        )
    }

    companion object {
        const val TAG = "engine"
        private const val KEY_ADVANCED = "advanced_expanded"
    }
}
