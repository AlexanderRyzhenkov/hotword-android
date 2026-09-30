package dev.hotword.android

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.hotword.android.assistant.AssistantLauncher
import dev.hotword.android.audio.TriggerMatcher
import dev.hotword.android.model.ModelInstaller
import dev.hotword.android.service.WakeWordService
import dev.hotword.android.settings.ModelLanguage
import dev.hotword.android.settings.Preferences

class MainActivity : Activity() {
    private lateinit var phraseInput: EditText
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var retryButton: Button
    private val ui = Handler(Looper.getMainLooper())
    private var preparing = false
    private val modelLanguage = ModelLanguage.RUSSIAN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        layout.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 25f
            setPadding(0, 0, 0, padding)
        })
        layout.addView(TextView(this).apply { text = getString(R.string.phrase_label) })
        phraseInput = EditText(this).apply {
            setSingleLine(true)
            setText(Preferences.phrase(this@MainActivity))
            hint = getString(R.string.phrase_hint)
        }
        layout.addView(phraseInput)
        layout.addView(TextView(this).apply { text = getString(R.string.language_russian_only) })
        status = TextView(this).apply { setPadding(0, padding / 2, 0, padding / 2) }
        layout.addView(status)
        retryButton = Button(this).apply {
            text = getString(R.string.retry_preparing)
            visibility = View.GONE
            setOnClickListener { prepareBundledModel() }
        }
        layout.addView(retryButton)
        startButton = Button(this).apply {
            text = getString(R.string.start)
            isEnabled = false
            setOnClickListener { startListening() }
        }
        layout.addView(startButton)
        layout.addView(Button(this).apply {
            text = getString(R.string.stop)
            setOnClickListener {
                WakeWordService.stop(this@MainActivity)
                status.text = getString(R.string.stopped)
            }
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

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(layout)
        }
        // targetSdk 35 enforces edge-to-edge. Use actual system and cutout insets
        // instead of a hard-coded status bar height, which is wrong on HyperOS.
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(
                    WindowInsets.Type.systemBars() or
                        WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
                )
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        } else {
            scroll.fitsSystemWindows = true
        }
        setContentView(scroll)
        prepareBundledModel()
    }

    private fun prepareBundledModel() {
        if (preparing) return
        if (ModelInstaller.isInstalled(this, modelLanguage)) {
            status.text = getString(R.string.model_ready)
            startButton.isEnabled = true
            retryButton.visibility = View.GONE
            return
        }
        preparing = true
        status.text = getString(R.string.model_preparing)
        retryButton.visibility = View.GONE
        startButton.isEnabled = false
        Thread({
            val result = runCatching {
                ModelInstaller.ensureInstalled(applicationContext, modelLanguage) { entries ->
                    ui.post {
                        if (!isFinishing && !isDestroyed) {
                            status.text = if (entries > 0)
                                getString(R.string.model_preparing_files, entries)
                            else getString(R.string.model_preparing)
                        }
                    }
                }
            }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                preparing = false
                if (result.isSuccess) {
                    status.text = getString(R.string.model_ready)
                    startButton.isEnabled = true
                } else {
                    status.text = getString(R.string.model_prepare_error,
                        result.exceptionOrNull()?.message ?: "unknown")
                    retryButton.visibility = View.VISIBLE
                }
            }
        }, "BundledModelPreparation").start()
    }

    private fun startListening(skipNotificationPermission: Boolean = false) {
        val phrase = phraseInput.text.toString().trim()
        if (TriggerMatcher.normalize(phrase).isBlank() || phrase.length > 100) {
            toast(getString(R.string.phrase_invalid))
            return
        }
        if (!ModelInstaller.isInstalled(this, modelLanguage)) {
            prepareBundledModel()
            return
        }
        Preferences.save(this, phrase)
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
            WakeWordService.stop(this)
            ui.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                try {
                    WakeWordService.start(this)
                    status.text = getString(R.string.start_requested)
                } catch (error: Exception) {
                    toast(error.message ?: getString(R.string.start_error))
                }
            }, 300)
        } catch (error: Exception) {
            toast(error.message ?: getString(R.string.start_error))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
        permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 100) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                startListening(skipNotificationPermission = true)
            else toast(getString(R.string.mic_required))
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
