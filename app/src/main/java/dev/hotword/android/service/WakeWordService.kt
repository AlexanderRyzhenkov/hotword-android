package dev.hotword.android.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import dev.hotword.android.MainActivity
import dev.hotword.android.R
import dev.hotword.android.assistant.AssistantLauncher
import dev.hotword.android.diagnostics.Diagnostics
import dev.hotword.android.audio.VoskWakeWordEngine
import dev.hotword.android.audio.WakeWordEngine
import dev.hotword.android.model.ModelInstaller
import dev.hotword.android.settings.ModelLanguage
import dev.hotword.android.settings.Preferences
import java.util.concurrent.Executors

/** Minimal persistent microphone foreground service. No manual start/stop actions. */
class WakeWordService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { job ->
        Thread(job, "HotwordAudio")
    }
    private var engine: WakeWordEngine? = null // worker only
    @Volatile private var running = false
    @Volatile private var suspended = false
    private var failures = 0
    private var lastEngineStart = 0L
    private var cpuLock: PowerManager.WakeLock? = null
    private val renewCpuLock: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            // Bounded leases avoid retaining a wake lock after a process/lifecycle error.
            runCatching {
                val lock = cpuLock ?: return@runCatching
                if (lock.isHeld) lock.release()
                lock.acquire(CPU_LOCK_TIMEOUT_MS)
            }.onFailure { Log.w(TAG, "Cannot keep recognition awake", it) }
            main.postDelayed(this, CPU_LOCK_RENEW_MS)
        }
    }
    private var phrase = "" // main thread, reloaded without destroying the foreground service
    private var assistantWindow = false // main thread
    private lateinit var overlay: AssistantOverlay

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = AssistantOverlay(this)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            LISTENING_CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW
        ))
        notifications.createNotificationChannel(NotificationChannel(
            ALERT_CHANNEL, getString(R.string.alert_channel), NotificationManager.IMPORTANCE_DEFAULT
        ))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_OVERLAY && running) {
            overlay.sync()
            Diagnostics.record(this, "Overlay window attached: " + overlay.isAttached)
            return START_STICKY
        }
        if (intent?.action == ACTION_RELOAD && running) {
            reloadPhrase()
            return START_STICKY
        }
        if (running) return START_STICKY
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !ModelInstaller.isInstalled(this, ModelLanguage.RUSSIAN)) {
            Diagnostics.record(this, "Listener not started: microphone permission or model missing")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification())
        } catch (error: Exception) {
            Diagnostics.record(this, "Foreground service start rejected: " + error.javaClass.simpleName)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        phrase = Preferences.phrase(this)
        isActive = true
        Preferences.markEverStarted(this)
        // An active foreground service may continue recording while its Activity is
        // gone. Holding a bounded, renewed CPU lease helps on screen-off devices.
        cpuLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, packageName + ":HotwordListening")
            .apply { setReferenceCounted(false) }
        renewCpuLock.run()
        overlay.sync() // Only creates a status dot after explicit user approval.
        Diagnostics.record(this, "Foreground microphone service started; overlay attached: " + overlay.isAttached)
        startEngine()
        // A system-managed restart may recover after process eviction, but is not
        // a bypass for force-stop, boot or OEM microphone/background restrictions.
        return START_STICKY
    }

    /** Rebuild only the recognizer: never tear down the microphone FGS on text edits. */
    private fun reloadPhrase() {
        phrase = Preferences.phrase(this)
        if (assistantWindow || suspended) return // next scheduled restart uses the new phrase
        suspended = true
        worker.execute {
            runCatching { engine?.pause() }
            runCatching { engine?.updatePhrase(phrase) }
            main.post {
                if (!running) return@post
                suspended = false
                Diagnostics.record(this, "Trigger phrase changed; reloading audio only")
                startEngine()
            }
        }
    }

    private fun startEngine() {
        if (!running || suspended) return
        worker.execute {
            if (!running || suspended) return@execute
            try {
                val existing = engine
                lastEngineStart = System.currentTimeMillis()
                if (existing != null) {
                    existing.updatePhrase(phrase)
                    existing.resume()
                } else {
                    val next = VoskWakeWordEngine(
                        ModelInstaller.destination(this, ModelLanguage.RUSSIAN), phrase
                    )
                    engine = next
                    next.start(
                        onTrigger = { main.post { triggered() } },
                        onError = { main.post { failed(it) } }
                    )
                }
                if (!running || suspended) {
                    engine?.pause()
                } else {
                    Diagnostics.record(this, "Recognizer listening")
                }
            } catch (error: Throwable) {
                runCatching { engine?.close() }
                engine = null
                main.post { failed(error) }
            }
        }
    }

    private fun triggered() {
        if (!running || suspended) return
        suspended = true
        assistantWindow = true
        failures = 0
        Diagnostics.record(this, "Trigger phrase detected")
        // The Vosk Model stays warm. Only SpeechService/AudioRecord are released.
        worker.execute {
            runCatching { engine?.pause() }
            main.post {
                if (!running) return@post
                overlay.sync()
                val attempted = AssistantLauncher.launch(this)
                Diagnostics.record(
                    this,
                    if (attempted) "Selected assistant launch requested (OS may block it)"
                    else "Selected assistant has no usable handler"
                )
                if (!attempted) {
                    showFailure(getString(R.string.assistant_launch_failed), assistantAction = true)
                }
                waitForAssistantMicrophone()
            }
        }
    }

    /**
     * A fixed 20-second cooldown made wake words seem broken after every launch.
     * Check when the assistant starts/stops recording (where Android reports it).
     * If the system hides other apps' recordings, resume after 3 seconds instead.
     */
    private fun waitForAssistantMicrophone() {
        val manager = getSystemService(AudioManager::class.java)
        val policy = RecognitionResumePolicy(SystemClock.elapsedRealtime())
        val poll = object : Runnable {
            override fun run() {
                if (!running || !assistantWindow) return
                val externalRecording = runCatching {
                    // Our own AudioRecord has already been stopped on worker.
                    manager.activeRecordingConfigurations.isNotEmpty()
                }.getOrDefault(false)
                if (policy.shouldResume(SystemClock.elapsedRealtime(), externalRecording)) {
                    assistantWindow = false
                    suspended = false
                    Diagnostics.record(this@WakeWordService, "Microphone handoff complete; resuming recognition")
                    startEngine()
                } else {
                    main.postDelayed(this, AUDIO_POLL_MS)
                }
            }
        }
        main.postDelayed(poll, AUDIO_POLL_MS)
    }

    private fun failed(error: Throwable) {
        if (!running || suspended) return
        Diagnostics.record(this, "Recognition error: " + error.javaClass.simpleName)
        if (System.currentTimeMillis() - lastEngineStart > STABLE_RESET_MS) failures = 0
        failures++
        suspended = true
        worker.execute {
            runCatching { engine?.close() }
            engine = null
            main.post {
                if (!running) return@post
                if (failures <= MAX_RECOVERY_ATTEMPTS) {
                    main.postDelayed({
                        if (running) {
                            suspended = false
                            startEngine()
                        }
                    }, RECOVERY_DELAY_MS * failures)
                } else {
                    showFailure(
                        getString(R.string.listener_failed, error.message ?: "unknown"),
                        assistantAction = false
                    )
                    stopSelf()
                }
            }
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, LISTENING_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.listener_active))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun showFailure(message: String, assistantAction: Boolean) {
        val openApp = PendingIntent.getActivity(
            this, 15, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        getSystemService(NotificationManager::class.java).notify(
            ALERT_ID, Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(getString(R.string.attention_required))
                .setContentText(message)
                .setContentIntent(
                    if (assistantAction) AssistantLauncher.notificationAction(this) ?: openApp
                    else openApp
                )
                .setAutoCancel(true)
                .build()
        )
    }

    override fun onDestroy() {
        Diagnostics.record(this, "Microphone foreground service stopped")
        running = false
        isActive = false
        suspended = true
        main.removeCallbacksAndMessages(null)
        runCatching { cpuLock?.takeIf { it.isHeld }?.release() }
        cpuLock = null
        overlay.remove()
        worker.execute {
            runCatching { engine?.close() }
            engine = null
        }
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HotwordService"
        private const val ACTION_RELOAD = "dev.hotword.android.RELOAD_PHRASE"
        private const val ACTION_REFRESH_OVERLAY = "dev.hotword.android.REFRESH_OVERLAY"
        private const val CPU_LOCK_TIMEOUT_MS = 10 * 60_000L
        private const val CPU_LOCK_RENEW_MS = 9 * 60_000L
        private const val LISTENING_CHANNEL = "hotword_listener_v2"
        private const val ALERT_CHANNEL = "hotword_attention"
        private const val NOTIFICATION_ID = 100
        private const val ALERT_ID = 101
        private const val AUDIO_POLL_MS = 350L
        private const val STABLE_RESET_MS = 60_000L
        private const val RECOVERY_DELAY_MS = 5_000L
        private const val MAX_RECOVERY_ATTEMPTS = 4

        @Volatile var isActive: Boolean = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WakeWordService::class.java))
        }

        fun reload(context: Context) {
            // This is sent only to an already-running FGS, while our settings Activity
            // is visible. It does not create a microphone service from the background.
            context.startService(
                Intent(context, WakeWordService::class.java).setAction(ACTION_RELOAD)
            )
        }

        fun refreshOverlay(context: Context) {
            if (isActive) {
                context.startService(
                    Intent(context, WakeWordService::class.java).setAction(ACTION_REFRESH_OVERLAY)
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
