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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.hotword.android.MainActivity
import dev.hotword.android.R
import dev.hotword.android.assistant.AssistantLauncher
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
    private val phrase by lazy { Preferences.phrase(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            LISTENING_CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW
        ))
        notifications.createNotificationChannel(NotificationChannel(
            ALERT_CHANNEL, getString(R.string.alert_channel), NotificationManager.IMPORTANCE_DEFAULT
        ))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !ModelInstaller.isInstalled(this, ModelLanguage.RUSSIAN)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification())
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        isActive = true
        Preferences.markEverStarted(this)
        startEngine()
        // Android 14+ forbids creating a microphone foreground service at boot.
        return START_NOT_STICKY
    }

    private fun startEngine() {
        if (!running || suspended) return
        worker.execute {
            if (!running || suspended) return@execute
            try {
                val next = VoskWakeWordEngine(
                    ModelInstaller.destination(this, ModelLanguage.RUSSIAN), phrase
                )
                engine = next
                lastEngineStart = System.currentTimeMillis()
                next.start(
                    onTrigger = { main.post { triggered() } },
                    onError = { main.post { failed(it) } }
                )
                if (!running || suspended) {
                    next.close()
                    if (engine === next) engine = null
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
        failures = 0
        // Release the microphone before starting the assistant.
        worker.execute {
            runCatching { engine?.close() }
            engine = null
            main.post {
                if (!running) return@post
                if (!AssistantLauncher.launch(this)) {
                    showFailure(getString(R.string.assistant_launch_failed), assistantAction = true)
                }
                main.postDelayed({
                    if (running) {
                        suspended = false
                        startEngine()
                    }
                }, ASSISTANT_WINDOW_MS)
            }
        }
    }

    private fun failed(error: Throwable) {
        if (!running || suspended) return
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
            .setSilent(true)
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
                .setContentIntent(if (assistantAction)
                    AssistantLauncher.notificationAction(this) else openApp)
                .setAutoCancel(true)
                .build()
        )
    }

    override fun onDestroy() {
        running = false
        isActive = false
        suspended = true
        main.removeCallbacksAndMessages(null)
        worker.execute {
            runCatching { engine?.close() }
            engine = null
        }
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val LISTENING_CHANNEL = "hotword_listener_v2"
        private const val ALERT_CHANNEL = "hotword_attention"
        private const val NOTIFICATION_ID = 100
        private const val ALERT_ID = 101
        private const val ASSISTANT_WINDOW_MS = 20_000L
        private const val STABLE_RESET_MS = 60_000L
        private const val RECOVERY_DELAY_MS = 5_000L
        private const val MAX_RECOVERY_ATTEMPTS = 4

        @Volatile var isActive: Boolean = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
