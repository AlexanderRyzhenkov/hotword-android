package dev.hotword.android

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.TextView
import android.widget.Toast
import dev.hotword.android.assistant.AssistantLauncher
import dev.hotword.android.audio.TriggerMatcher
import dev.hotword.android.model.ModelInstaller
import dev.hotword.android.service.WakeWordService
import dev.hotword.android.settings.ModelLanguage
import dev.hotword.android.settings.Preferences

class MainActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var language: Spinner
    private lateinit var status: TextView
    private lateinit var downloadButton: Button
    private lateinit var startButton: Button
    private val ui = Handler(Looper.getMainLooper())
    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 25f
            setPadding(0, 0, 0, padding)
        }
        layout.addView(title)
        layout.addView(TextView(this).apply { text = getString(R.string.phrase_label) })
        input = EditText(this).apply {
            setSingleLine(true)
            setText(Preferences.phrase(this@MainActivity))
            hint = getString(R.string.phrase_hint)
        }
        layout.addView(input)
        layout.addView(TextView(this).apply { text = getString(R.string.language_label) })
        language = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.russian), getString(R.string.english)))
            setSelection(if (Preferences.language(this@MainActivity) == ModelLanguage.RUSSIAN) 0 else 1)
        }
        layout.addView(language)
        status = TextView(this).apply { setPadding(0, padding / 2, 0, padding / 2) }
        layout.addView(status)
        downloadButton = Button(this).apply {
            text = getString(R.string.download)
            setOnClickListener { downloadModel() }
        }
        layout.addView(downloadButton)
        startButton = Button(this).apply {
            text = getString(R.string.start)
            setOnClickListener { startListening() }
        }
        layout.addView(startButton)
        layout.addView(Button(this).apply {
            text = getString(R.string.stop)
            setOnClickListener { WakeWordService.stop(this@MainActivity); status.text = getString(R.string.stopped) }
        })
        layout.addView(Button(this).apply {
            text = getString(R.string.test_assistant)
            setOnClickListener {
                if (!AssistantLauncher.launch(this@MainActivity)) toast(getString(R.string.assistant_error))
            }
        })
        layout.addView(TextView(this).apply {
            text = getString(R.string.usage_warning)
            setPadding(0, padding, 0, 0)
        })
        val scroll = android.widget.ScrollView(this).apply { addView(layout) }
        setContentView(scroll)
        language.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (!downloading) updateModelStatus()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateModelStatus()
    }

    private fun selectedLanguage(): ModelLanguage =
        if (language.selectedItemPosition == 0) ModelLanguage.RUSSIAN else ModelLanguage.ENGLISH

    private fun updateModelStatus() {
        val ready = ModelInstaller.isInstalled(this, selectedLanguage())
        status.text = if (ready) getString(R.string.model_ready) else getString(R.string.model_missing)
        downloadButton.isEnabled = !downloading && !ready
    }

    private fun saveSettings(): Boolean {
        val phrase = input.text.toString().trim()
        if (TriggerMatcher.normalize(phrase).isBlank() || phrase.length > 100) {
            toast(getString(R.string.phrase_invalid))
            return false
        }
        Preferences.save(this, phrase, selectedLanguage())
        return true
    }

    private fun downloadModel() {
        val chosen = selectedLanguage()
        if (downloading || ModelInstaller.isInstalled(this, chosen)) return
        downloading = true
        downloadButton.isEnabled = false
        startButton.isEnabled = false
        Thread({
            try {
                ModelInstaller.download(applicationContext, chosen) { message ->
                    ui.post { status.text = message }
                }
                ui.post { status.text = getString(R.string.model_ready) }
            } catch (error: Throwable) {
                ui.post { status.text = getString(R.string.download_error, error.message ?: "unknown") }
            } finally {
                ui.post {
                    downloading = false
                    startButton.isEnabled = true
                    downloadButton.isEnabled = !ModelInstaller.isInstalled(this, chosen)
                }
            }
        }, "ModelDownload").start()
    }

    private fun startListening(skipNotificationPermission: Boolean = false) {
        if (!saveSettings()) return
        if (!ModelInstaller.isInstalled(this, selectedLanguage())) {
            toast(getString(R.string.model_missing))
            return
        }
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.RECORD_AUDIO)
        if (!skipNotificationPermission && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
            return
        }
        try {
            WakeWordService.stop(this) // A changed phrase must be picked up at next service start.
            ui.postDelayed({
                try {
                    WakeWordService.start(this)
                    status.text = getString(R.string.start_requested)
                } catch (error: Exception) { toast(error.message ?: getString(R.string.start_error)) }
            }, 300)
        } catch (error: Exception) {
            toast(error.message ?: getString(R.string.start_error))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 100) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                // Notification permission is optional; fallback notification may be hidden if denied.
                startListening(skipNotificationPermission = true)
            } else toast(getString(R.string.mic_required))
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
