package dev.hotword.android

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.hotword.android.assistant.AssistantLauncher
import dev.hotword.android.audio.TriggerChime
import dev.hotword.android.audio.TriggerMatcher
import dev.hotword.android.diagnostics.AppVisibility
import dev.hotword.android.diagnostics.Diagnostics
import dev.hotword.android.model.ModelInstaller
import dev.hotword.android.service.WakeWordService
import dev.hotword.android.settings.ModelLanguage
import dev.hotword.android.settings.Preferences
import dev.hotword.android.setup.DeviceSetup
import dev.hotword.android.setup.SetupRequirements
import dev.hotword.android.setup.SetupRequirements.Requirement

/**
 * One screen for phrase/settings plus a strict, universal Android readiness
 * checklist. Long-running listening has no manual start/stop switch.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var setupSummary: TextView
    private lateinit var diagnosticsText: TextView
    private val setupRows = mutableMapOf<Requirement, Pair<TextView, Button>>()

    private var modelReady = false
    private var preparing = false
    private var resumed = false
    private var micRequested = false
    private var notificationRequested = false
    private var permissionRequestInFlight = false
    private var phraseApply: Runnable? = null
    private var diagnosticsExpanded = false

    private val refreshDiagnostics = object : Runnable {
        override fun run() {
            if (!resumed || !diagnosticsExpanded) return
            diagnosticsText.text = Diagnostics.recent(this@MainActivity)
                .ifBlank { getString(R.string.diagnostics_empty) }
            ui.postDelayed(this, 2_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gap = (16 * resources.displayMetrics.density).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gap, gap, gap, gap)
        }

        column.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(
                (72 * resources.displayMetrics.density).toInt(),
                (72 * resources.displayMetrics.density).toInt()
            )
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 25f
            setPadding(0, gap / 2, 0, 0)
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.app_tagline)
            setPadding(0, gap / 3, 0, gap)
        })

        column.addView(TextView(this).apply { text = getString(R.string.phrase_label) })
        input = EditText(this).apply {
            setSingleLine(true)
            setText(Preferences.phrase(this@MainActivity))
            hint = getString(R.string.phrase_hint)
        }
        column.addView(input)
        column.addView(TextView(this).apply {
            text = getString(R.string.phrase_apply_hint)
        })

        column.addView(Switch(this).apply {
            text = getString(R.string.trigger_sound)
            isChecked = Preferences.triggerSoundEnabled(this@MainActivity)
            setPadding(0, gap / 2, 0, gap / 2)
            setOnCheckedChangeListener { _, enabled ->
                Preferences.setTriggerSoundEnabled(this@MainActivity, enabled)
                if (enabled) {
                    Thread({
                        runCatching { TriggerChime.playBlocking() }
                    }, "TriggerChimePreview").start()
                }
            }
        })

        status = TextView(this).apply { setPadding(0, gap / 2, 0, gap) }
        column.addView(status)

        column.addView(TextView(this).apply {
            text = getString(R.string.setup_heading)
            textSize = 20f
        })
        setupSummary = TextView(this).apply { setPadding(0, gap / 3, 0, gap / 2) }
        column.addView(setupSummary)

        addSetupRow(column, Requirement.MICROPHONE, R.string.setup_microphone) {
            if (Build.VERSION.SDK_INT >= 23) {
                micRequested = true
                permissionRequestInFlight = true
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            }
        }
        addSetupRow(column, Requirement.NOTIFICATIONS, R.string.setup_notifications) {
            if (Build.VERSION.SDK_INT >= 33) {
                notificationRequested = true
                permissionRequestInFlight = true
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            } else {
                DeviceSetup.openNotificationSettings(this)
            }
        }
        addSetupRow(column, Requirement.BACKGROUND_LAUNCH, R.string.setup_background_launch) {
            DeviceSetup.openOverlaySettings(this)
        }
        addSetupRow(column, Requirement.BATTERY_UNRESTRICTED, R.string.setup_battery) {
            DeviceSetup.openBatterySettings(this)
        }
        addSetupRow(column, Requirement.BACKGROUND_NOT_RESTRICTED, R.string.setup_background_access) {
            DeviceSetup.openBackgroundSettings(this)
        }

        column.addView(Button(this).apply {
            text = getString(R.string.test_assistant)
            setOnClickListener {
                if (!AssistantLauncher.launch(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, R.string.assistant_error, Toast.LENGTH_LONG).show()
                }
            }
        })

        column.addView(TextView(this).apply {
            text = getString(R.string.privacy_note)
            setPadding(0, gap, 0, gap / 2)
        })

        column.addView(TextView(this).apply {
            text = getString(R.string.diagnostics_heading)
            textSize = 18f
            setPadding(0, gap, 0, 0)
        })
        diagnosticsText = TextView(this).apply { visibility = View.GONE }
        column.addView(diagnosticsText)
        val diagnosticsButton = Button(this).apply {
            text = getString(R.string.diagnostics_show)
        }
        diagnosticsButton.setOnClickListener {
            diagnosticsExpanded = !diagnosticsExpanded
            diagnosticsText.visibility = if (diagnosticsExpanded) View.VISIBLE else View.GONE
            diagnosticsButton.text = getString(
                if (diagnosticsExpanded) R.string.diagnostics_hide else R.string.diagnostics_show
            )
            ui.removeCallbacks(refreshDiagnostics)
            if (diagnosticsExpanded && resumed) refreshDiagnostics.run()
        }
        column.addView(diagnosticsButton)
        column.addView(Button(this).apply {
            text = getString(R.string.diagnostics_copy)
            setOnClickListener {
                val text = Diagnostics.recent(this@MainActivity)
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Hotword diagnostics", text))
                Toast.makeText(
                    this@MainActivity,
                    R.string.diagnostics_copied,
                    Toast.LENGTH_SHORT
                ).show()
            }
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
        } else {
            scroll.fitsSystemWindows = true
        }
        setContentView(scroll)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                phraseApply?.let(ui::removeCallbacks)
                val next = s?.toString()?.trim().orEmpty()
                if (next == Preferences.phrase(this@MainActivity)) return
                phraseApply = Runnable { applyPhrase(next) }.also {
                    ui.postDelayed(it, 1_500L)
                }
            }
        })

        prepareModel()
    }

    private fun addSetupRow(
        parent: LinearLayout,
        requirement: Requirement,
        labelRes: Int,
        action: () -> Unit
    ) {
        val label = TextView(this).apply { setPadding(0, 6, 0, 0) }
        val button = Button(this).apply {
            text = getString(R.string.setup_fix)
            setOnClickListener { action() }
        }
        parent.addView(label)
        parent.addView(button)
        setupRows[requirement] = label to button
        label.tag = labelRes
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        AppVisibility.isActivityResumed = true
        updateSetup()
        requestRuntimePermissionsOnce()
        if (modelReady) ensureListening()
        if (diagnosticsExpanded) refreshDiagnostics.run()
    }

    override fun onPause() {
        phraseApply?.let(ui::removeCallbacks)
        phraseApply = null
        if (::input.isInitialized) applyPhrase(input.text.toString().trim())
        resumed = false
        AppVisibility.isActivityResumed = false
        ui.removeCallbacks(refreshDiagnostics)
        super.onPause()
    }

    private fun requestRuntimePermissionsOnce() {
        if (permissionRequestInFlight) return
        if (!DeviceSetup.microphoneGranted(this) && !micRequested) {
            micRequested = true
            permissionRequestInFlight = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            !DeviceSetup.notificationsGranted(this) &&
            !notificationRequested
        ) {
            notificationRequested = true
            permissionRequestInFlight = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun prepareModel() {
        if (preparing) return
        if (ModelInstaller.isInstalled(this, ModelLanguage.RUSSIAN)) {
            modelReady = true
            updateSetup()
            ensureListening()
            return
        }

        preparing = true
        status.text = getString(R.string.model_preparing)
        Thread({
            val result = runCatching {
                ModelInstaller.ensureInstalled(applicationContext, ModelLanguage.RUSSIAN) { files ->
                    ui.post {
                        if (!isDestroyed) {
                            status.text = if (files > 0) {
                                getString(R.string.model_preparing_files, files)
                            } else {
                                getString(R.string.model_preparing)
                            }
                        }
                    }
                }
            }
            ui.post {
                if (isDestroyed || isFinishing) return@post
                preparing = false
                if (result.isSuccess) {
                    modelReady = true
                    updateSetup()
                    requestRuntimePermissionsOnce()
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

        try {
            if (WakeWordService.isActive) {
                WakeWordService.reload(this)
            } else if (resumed) {
                ensureListening()
            }
        } catch (_: Exception) {
            status.text = getString(R.string.start_error)
        }
    }

    private fun ensureListening() {
        if (!modelReady || !resumed || permissionRequestInFlight || isDestroyed || isFinishing) {
            return
        }

        val missing = SetupRequirements.missing(this)
        if (missing.isNotEmpty()) {
            status.text = getString(R.string.setup_incomplete)
            updateSetup()
            return
        }

        if (!WakeWordService.isActive) {
            try {
                WakeWordService.start(this)
                status.text = getString(R.string.start_requested)
            } catch (_: Exception) {
                status.text = getString(R.string.start_error)
            }
        } else {
            status.text = getString(R.string.running)
        }
        updateSetup()
    }

    private fun updateSetup() {
        if (!::setupSummary.isInitialized) return
        val missing = SetupRequirements.missing(this)
        setupSummary.text = getString(
            if (missing.isEmpty()) R.string.setup_ready else R.string.setup_needs_attention
        )

        for ((requirement, row) in setupRows) {
            val (label, button) = row
            val nameRes = label.tag as Int
            val ok = requirement !in missing
            label.text = getString(
                if (ok) R.string.setup_item_ready else R.string.setup_item_missing,
                getString(nameRes)
            )
            button.visibility = if (ok) View.GONE else View.VISIBLE
        }

        if (modelReady && missing.isEmpty()) {
            status.text = if (WakeWordService.isActive) {
                getString(R.string.running)
            } else {
                getString(R.string.ready_to_start)
            }
        } else if (modelReady) {
            status.text = getString(R.string.setup_incomplete)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionRequestInFlight = false
        updateSetup()
        requestRuntimePermissionsOnce()
        if (modelReady) ensureListening()
    }

    override fun onDestroy() {
        AppVisibility.isActivityResumed = false
        phraseApply?.let(ui::removeCallbacks)
        ui.removeCallbacks(refreshDiagnostics)
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_MIC = 100
        private const val REQUEST_NOTIFICATIONS = 101
    }
}
