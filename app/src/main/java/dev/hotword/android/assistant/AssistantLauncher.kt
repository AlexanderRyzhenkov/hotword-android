package dev.hotword.android.assistant

import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/** Only invokes the assistant selected by the user in Android settings. */
object AssistantLauncher {
    private fun assistIntent() = Intent(Intent.ACTION_ASSIST).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(Intent.EXTRA_ASSIST_INPUT_HINT_KEYBOARD, false)
    }

    private fun selectedPackage(context: Context): String? {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                roles.getRoleHolders(RoleManager.ROLE_ASSISTANT).firstOrNull()?.let { return it }
            }
        }
        val component = Settings.Secure.getString(context.contentResolver, "assistant")
        ComponentName.unflattenFromString(component ?: "")?.packageName?.let { return it }
        @Suppress("DEPRECATION")
        val resolved = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_ASSIST), PackageManager.MATCH_DEFAULT_ONLY
        )
        return resolved?.activityInfo?.packageName
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
