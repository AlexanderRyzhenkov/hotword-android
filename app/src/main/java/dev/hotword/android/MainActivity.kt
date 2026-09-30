package dev.hotword.android

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
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
import dev.hotword.android.setup.DeviceSetup

/**
 * Settings/status screen. Listening itself has no start/stop toggle: once the model and
 * microphone permission are ready, the foreground service is started automatically.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var permissionStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var autostartStatus: TextView
    private var modelReady = false
    private var preparing = false
    private var restarting = false
    private var micRequested = false
    private var notificationRequested = false
    private var phraseApply: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gap = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gap, gap, gap, gap)
        }
        column.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 25f
            setPadding(0, 0, 0, gap)
        })
        column.addView(TextView(this).apply { text = getString(R.string.phrase_label) })
        input = EditText(this).apply {
            setSingleLine(true)
            setText(Preferences.phrase(this@MainActivity))
            hint = getString(R.string.phrase_hint)
        }
        column.addView(input)
        column.addView(TextView(this).apply { text = getString(R.string.phrase_apply_hint) })
        column.addView(TextView(this).apply { text = getString(R.string.language_russian_only) })

        status = TextView(this).apply { setPadding(0, gap, 0, gap) }
        column.addView(status)

        column.addView(TextView(this).apply {
            text = getString(R.string.setup_heading)
            textSize = 20f
        })
        permissionStatus = TextView(this)
        column.addView(permissionStatus)
        column.addView(Button(this).apply {
            text = getString(R.string.app_settings)
            setOnClickListener { DeviceSetup.openAppSettings(this@MainActivity) }
        })

        batteryStatus = TextView(this).apply { setPadding(0, gap / 2, 0, 0) }
        column.addView(batteryStatus)
        column.addView(Button(this).apply {
            text = getString(R.string.battery_settings)
            setOnClickListener { DeviceSetup.openBatterySettings(this@MainActivity) }
        })

        autostartStatus = TextView(this).apply { setPadding(0, gap / 2, 0, 0) }
        column.addView(autostartStatus)
        column.addView(Button(this).apply {
            text = getString(R.string.autostart_settings)
            setOnClickListener { DeviceSetup.openAutostartSettings(this@MainActivity) }
        })

        column.addView(Button(this).apply {
            text = getString(R.string.test_assistant)
            setOnClickListener {
                if (!AssistantLauncher.launch(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, R.string.assistant_error, Toast.LENGTH_LONG).show()
                }
            }
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.usage_warning)
            setPadding(0, gap, 0, 0)
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(column)
        }
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
        } else scroll.fitsSystemWindows = true
        setContentView(scroll)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                phraseApply?.let(ui::removeCallbacks)
                val next = s?.toString()?.trim().orEmpty()
                if (next == Preferences.phrase(this@MainActivity)) return
                phraseApply = Runnable { applyPhrase(next) }.also { ui.postDelayed(it, 1_500) }
            }
        })
        prepareModel()
    }

    override fun onResume() {
        super.onResume()
        updateChecks()
        if (modelReady && !restarting) ensureListening()
    }

    private fun prepareModel() {
        if (preparing) return
        if (ModelInstaller.isInstalled(this, ModelLanguage.RUSSIAN)) {
            modelReady = true
            status.text = getString(R.string.active_preparing)
            ensureListening()
            return
        }
        preparing = true
        status.text = getString(R.string.model_preparing)
        Thread({
            val result = runCatching {
                ModelInstaller.ensureInstalled(applicationContext, ModelLanguage.RUSSIAN) { files ->
                    ui.post {
                        if (!isDestroyed) status.text =
                            if (files > 0) getString(R.string.model_preparing_files, files)
                            else getString(R.string.model_preparing)
                    }
                }
            }
            ui.post {
                if (isDestroyed || isFinishing) return@post
                preparing = false
                if (result.isSuccess) {
                    modelReady = true
                    ensureListening()
                } else {
                    status.text = getString(
                        R.string.model_prepare_error,
                        result.exceptionOrNull()?.message ?: "unknown"
                    )
                }
            }
        }, "BundledModelPreparation").start()
    }

    private fun applyPhrase(raw: String) {
        val phrase = raw.trim()
        if (phrase == Preferences.phrase(this)) return
        if (phrase.length > 100 || TriggerMatcher.normalize(phrase).isBlank()) {
            status.text = getString(R.string.phrase_invalid)
            return
        }
        Preferences.save(this, phrase)
        if (!modelReady || !DeviceSetup.microphoneGranted(this)) return
        restarting = true
        status.text = getString(R.string.restarting)
        WakeWordService.stop(this)
        ui.postDelayed({
            restarting = false
            if (!isDestroyed && !isFinishing) ensureListening()
        }, 700)
    }

    private fun ensureListening() {
        if (!modelReady || restarting || isDestroyed || isFinishing) return
        if (!DeviceSetup.microphoneGranted(this)) {
            status.text = getString(R.string.mic_required)
            if (!micRequested) {
                micRequested = true
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            !DeviceSetup.notificationsGranted(this) && !notificationRequested) {
            notificationRequested = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        if (!WakeWordService.isActive) {
            try {
                WakeWordService.start(this)
                status.text = getString(R.string.start_requested)
            } catch (error: Exception) {
                status.text = getString(R.string.start_error)
            }
        } else {
            status.text = getString(R.string.running)
        }
        updateChecks()
    }

    private fun updateChecks() {
        if (!::permissionStatus.isInitialized) return
        permissionStatus.text = getString(
            R.string.permission_check,
            if (DeviceSetup.microphoneGranted(this)) "✓" else "✕",
            if (DeviceSetup.notificationsEnabled(this)) "✓" else "✕"
        )
        batteryStatus.text = getString(
            R.string.battery_check,
            if (DeviceSetup.batteryExempt(this)) getString(R.string.battery_exempt)
            else getString(R.string.battery_restricted),
            if (DeviceSetup.backgroundRestricted(this)) getString(R.string.background_restricted)
            else getString(R.string.background_not_restricted)
        )
        autostartStatus.text = getString(R.string.autostart_unverifiable)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateChecks()
        if ((requestCode == REQUEST_MIC || requestCode == REQUEST_NOTIFICATIONS) &&
            modelReady && !restarting) ensureListening()
    }

    override fun onDestroy() {
        phraseApply?.let(ui::removeCallbacks)
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_MIC = 100
        private const val REQUEST_NOTIFICATIONS = 101
    }
}
