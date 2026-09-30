package dev.hotword.android.assistant

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/** Only invokes the assistant selected by the user in Android settings. */
object AssistantLauncher {
    private fun assistIntent() = Intent(Intent.ACTION_ASSIST).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(Intent.EXTRA_ASSIST_INPUT_HINT_KEYBOARD, false)
    }

    private fun selectedPackage(context: Context): String? {
        // Prefer the ACTION_ASSIST target that Android exposes as the active handler.
        // VoiceInteractionManager's actual active service is a privileged API.
        val chosen = Settings.Secure.getString(context.contentResolver, "assistant")
        val chosenPackage = ComponentName.unflattenFromString(chosen ?: "")?.packageName
        @Suppress("DEPRECATION")
        val resolved = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_ASSIST), PackageManager.MATCH_DEFAULT_ONLY
        )
        val activityPackage = resolved?.activityInfo?.packageName
        return if (activityPackage != null && activityPackage != "android" &&
            activityPackage != "com.android.systemui") activityPackage else chosenPackage
    }

    private fun selectedVoiceCommand(context: Context): Intent? {
        val target = selectedPackage(context) ?: return null
        val candidate = Intent(Intent.ACTION_VOICE_COMMAND).setPackage(target)
        @Suppress("DEPRECATION")
        val handlers = context.packageManager.queryIntentActivities(
            candidate, PackageManager.MATCH_DEFAULT_ONLY
        )
        if (handlers.none { it.activityInfo?.exported == true }) return null
        return candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun launch(context: Context): Boolean {
        val voice = runCatching { selectedVoiceCommand(context) }.getOrNull()
        if (voice != null && runCatching { context.startActivity(voice) }.isSuccess) return true
        // Public API fallback. This cannot perfectly impersonate a privileged SystemUI gesture.
        return runCatching { context.startActivity(assistIntent()) }.isSuccess
    }

    fun notificationAction(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 9, assistIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
