package com.github.aljge.tensorspeak

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.github.aljge.tensorspeak.benchmark.SynthesisBenchmarkRunner
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class BenchmarkActivity : AppCompatActivity() {

    private val runner by lazy { SynthesisBenchmarkRunner(this) }
    private var benchmarkJob: Job? = null
    private val cancelled = AtomicBoolean(false)
    private var activeEngine: OnnxTts? = null
    private var lastReportText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_benchmark)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val deviceInfo = findViewById<TextView>(R.id.device_info)
        val info = runner.deviceInfo()
        deviceInfo.text = getString(
            R.string.benchmark_device_header,
            info.model,
            info.sdk,
            info.cores,
            info.thermal,
        )

        val presetGroup = findViewById<RadioGroup>(R.id.preset_group)
        val progress = findViewById<LinearProgressIndicator>(R.id.progress)
        val progressStatus = findViewById<TextView>(R.id.progress_status)
        val results = findViewById<TextView>(R.id.results)
        val run = findViewById<MaterialButton>(R.id.run)
        val cancel = findViewById<MaterialButton>(R.id.cancel)
        val share = findViewById<MaterialButton>(R.id.share)
        val copy = findViewById<MaterialButton>(R.id.copy)

        fun setRunning(running: Boolean) {
            run.isEnabled = !running
            cancel.isEnabled = running
            presetGroup.isEnabled = !running
            progress.visibility = if (running) View.VISIBLE else View.GONE
            progressStatus.visibility = if (running) View.VISIBLE else View.GONE
            if (running) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        run.setOnClickListener {
            if (benchmarkJob?.isActive == true) return@setOnClickListener
            cancelled.set(false)
            lastReportText = ""
            results.text = ""
            share.isEnabled = false
            copy.isEnabled = false
            setRunning(true)
            progressStatus.text = ""

            val spec = if (findViewById<android.widget.RadioButton>(R.id.preset_compare).isChecked) {
                SynthesisBenchmarkRunner.BenchmarkSpec.CompareBackends(
                    variant = ModelPreferences.get(this),
                )
            } else {
                SynthesisBenchmarkRunner.BenchmarkSpec.QuickCurrent(
                    variant = ModelPreferences.get(this),
                    config = ModelPreferences.runtimeConfig(this),
                )
            }

            benchmarkJob = lifecycleScope.launch {
                val lines = mutableListOf<String>()
                try {
                    val report = runner.run(
                        spec = spec,
                        shouldContinue = { !cancelled.get() },
                        onProgress = { p ->
                            runOnUiThread {
                                progressStatus.text = when (p.phase) {
                                    SynthesisBenchmarkRunner.BenchmarkProgress.Phase.LOADING ->
                                        getString(R.string.benchmark_progress_loading, p.configLabel)
                                    SynthesisBenchmarkRunner.BenchmarkProgress.Phase.WARMUP ->
                                        getString(R.string.benchmark_progress_warmup, p.configLabel)
                                    SynthesisBenchmarkRunner.BenchmarkProgress.Phase.MEASURING ->
                                        getString(
                                            R.string.benchmark_progress_case,
                                            p.configLabel,
                                            p.caseLabel,
                                            p.caseIndex + 1,
                                            p.caseCount,
                                        )
                                    SynthesisBenchmarkRunner.BenchmarkProgress.Phase.DONE ->
                                        getString(R.string.benchmark_done)
                                    SynthesisBenchmarkRunner.BenchmarkProgress.Phase.CANCELLED ->
                                        getString(R.string.benchmark_cancelled)
                                    else -> ""
                                }
                            }
                        },
                        onLine = { line ->
                            lines += line
                            runOnUiThread {
                                results.text = lines.joinToString("\n")
                            }
                        },
                        activeEngine = { engine ->
                            activeEngine = engine
                        },
                    )
                    lastReportText = report.asText()
                    runOnUiThread {
                        results.text = lastReportText
                        progressStatus.text = if (report.cancelled) {
                            getString(R.string.benchmark_cancelled)
                        } else {
                            getString(R.string.benchmark_done)
                        }
                        share.isEnabled = lastReportText.isNotBlank()
                        copy.isEnabled = lastReportText.isNotBlank()
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        progressStatus.text = getString(R.string.synthesis_failed, error.message.orEmpty())
                    }
                } finally {
                    activeEngine = null
                    runOnUiThread { setRunning(false) }
                }
            }
        }

        cancel.setOnClickListener {
            cancelled.set(true)
            activeEngine?.requestStop()
        }

        share.setOnClickListener {
            if (lastReportText.isBlank()) return@setOnClickListener
            startActivity(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.benchmark_title))
                    putExtra(Intent.EXTRA_TEXT, lastReportText)
                },
            )
        }

        copy.setOnClickListener {
            if (lastReportText.isBlank()) return@setOnClickListener
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.benchmark_title), lastReportText))
            android.widget.Toast.makeText(this, R.string.benchmark_copied, android.widget.Toast.LENGTH_SHORT).show()
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (benchmarkJob?.isActive == true) {
                        cancelled.set(true)
                        activeEngine?.requestStop()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    override fun onDestroy() {
        cancelled.set(true)
        activeEngine?.requestStop()
        benchmarkJob?.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
