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
import dev.hotword.android.settings.Preferences
import java.util.concurrent.Executors

/** User-initiated microphone foreground service; never automatically launched on boot. */
class WakeWordService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    // Serializes native model initialization/closing; never close a model while it is opening.
    private val audioWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HotwordAudioWorker")
    }
    private var engine: WakeWordEngine? = null // audioWorker only
    @Volatile private var running = false
    @Volatile private var suspended = false
    private var failed = false
    private val language by lazy { Preferences.language(this) }
    private val phrase by lazy { Preferences.phrase(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !ModelInstaller.isInstalled(this, language)) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        try {
            startForeground(NOTIFICATION_ID, notification(getString(R.string.listening, phrase)))
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        startEngine()
        // System must not auto-restart a microphone FGS while the app is in the background.
        return START_NOT_STICKY
    }

    private fun startEngine() {
        if (!running || suspended) return
        audioWorker.execute {
            if (!running || suspended) return@execute
            try {
                val next = VoskWakeWordEngine(ModelInstaller.destination(this, language), phrase)
                engine = next
                next.start(
                    onTrigger = { mainHandler.post { onTriggered() } },
                    onError = { mainHandler.post { onFailure(it) } }
                )
                if (!running || suspended) {
                    next.close()
                    if (engine === next) engine = null
                }
            } catch (error: Throwable) {
                engine?.close()
                engine = null
                mainHandler.post { onFailure(error) }
            }
        }
    }

    private fun onTriggered() {
        if (!running || suspended) return
        suspended = true
        // Drop our AudioRecord before handing the microphone to the chosen assistant.
        audioWorker.execute {
            engine?.close()
            engine = null
            mainHandler.post {
                if (!running) return@post
                val notifications = getSystemService(NotificationManager::class.java)
                notifications.notify(ALERT_ID, notification(getString(R.string.triggered), assistantAction = true))
                // Background activity launch may be silently blocked. Notification is the fallback.
                AssistantLauncher.launch(this)
                mainHandler.postDelayed({
                    if (running) {
                        suspended = false
                        notifications.cancel(ALERT_ID)
                        notifications.notify(NOTIFICATION_ID, notification(getString(R.string.listening, phrase)))
                        startEngine()
                    }
                }, 20_000)
            }
        }
    }

    private fun onFailure(error: Throwable) {
        if (!running || suspended) return
        failed = true
        getSystemService(NotificationManager::class.java).notify(
            ALERT_ID, notification(getString(R.string.error_notification, error.message ?: "unknown"))
        )
        stopSelf() // Do not busy-loop after a microphone or native engine failure.
    }

    private fun notification(message: String, assistantAction: Boolean = false): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setContentIntent(if (assistantAction) AssistantLauncher.notificationAction(this) else openApp)
            .setOngoing(!assistantAction)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .apply {
                if (assistantAction) addAction(Notification.Action.Builder(
                    null, getString(R.string.open_assistant), AssistantLauncher.notificationAction(this@WakeWordService)
                ).build())
            }
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onDestroy() {
        running = false
        suspended = true
        mainHandler.removeCallbacksAndMessages(null)
        audioWorker.execute {
            engine?.close()
            engine = null
        }
        audioWorker.shutdown()
        if (!failed) getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
        super.onDestroy()
    }

    companion object {
        private const val STOP = "dev.hotword.android.STOP"
        private const val CHANNEL = "hotword_listener"
        private const val NOTIFICATION_ID = 100
        private const val ALERT_ID = 101
        fun start(context: Context) {
            context.startForegroundService(Intent(context, WakeWordService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
