package com.github.aljge.tensorspeak

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText

class TryFragment : Fragment() {
    private var statusView: TextView? = null
    private var speakButton: MaterialButton? = null
    private var metricsGroup: ChipGroup? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_try, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val host = requireActivity() as MainActivity
        statusView = view.findViewById(R.id.status)
        speakButton = view.findViewById(R.id.speak)
        metricsGroup = view.findViewById(R.id.metrics)
        val input = view.findViewById<TextInputEditText>(R.id.input)

        if (input.text.isNullOrBlank()) {
            input.setText(DEMO_TEXT)
        }
        statusView?.text = host.cachedStatus
        speakButton?.isEnabled = host.canSpeak
        speakButton?.setOnClickListener {
            host.onSpeakClicked(
                text = input.text?.toString()?.trim().orEmpty(),
                status = statusView!!,
                speak = speakButton!!,
                metrics = metricsGroup!!,
            )
        }
    }

    fun setStatus(text: String) {
        statusView?.text = text
    }

    fun setSpeakEnabled(enabled: Boolean) {
        speakButton?.isEnabled = enabled
        if (enabled) {
            speakButton?.text = getString(R.string.speak)
        }
    }

    fun clearMetrics() {
        metricsGroup?.visibility = View.GONE
        metricsGroup?.removeAllViews()
    }

    fun showMetrics(audioSeconds: Float, totalMs: Long, ttfaMs: Long) {
        val group = metricsGroup ?: return
        group.removeAllViews()
        group.addView(metricChip(getString(R.string.metric_audio, audioSeconds)))
        group.addView(metricChip(getString(R.string.metric_total, totalMs)))
        if (ttfaMs >= 0L) {
            group.addView(metricChip(getString(R.string.metric_ttfa, ttfaMs)))
        }
        if (audioSeconds > 0f) {
            val rtf = totalMs / 1000.0 / audioSeconds
            group.addView(metricChip(getString(R.string.metric_rtf, rtf)))
        }
        group.visibility = View.VISIBLE
    }

    private fun metricChip(label: String): Chip =
        Chip(requireContext()).apply {
            text = label
            isCheckable = false
            isClickable = false
        }

    companion object {
        const val TAG = "try"
        private const val DEMO_TEXT = "A small voice can still have something meaningful to say."
    }
}
