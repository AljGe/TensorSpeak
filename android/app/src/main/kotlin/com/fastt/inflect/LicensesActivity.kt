package com.fastt.inflect

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Offline open-source license texts (GPL-3.0 app + eSpeak, Apache-2.0 models, MIT ORT).
 */
class LicensesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_licenses)

        val body = findViewById<TextView>(R.id.licenses_body)
        body.text = buildString {
            appendSection(R.raw.license_gpl3)
            append("\n\n────────\n\n")
            appendSection(R.raw.license_apache2)
            append("\n\n────────\n\n")
            appendSection(R.raw.license_mit_onnxruntime)
            append("\n\n────────\n\n")
            appendSection(R.raw.license_espeak)
        }
    }

    private fun StringBuilder.appendSection(rawId: Int) {
        resources.openRawResource(rawId).bufferedReader().use { append(it.readText()) }
    }
}
