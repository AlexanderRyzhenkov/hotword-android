package dev.hotword.android.assistant

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Invoke only the assistant selected in Android settings. NEVER dispatch a
 * generic implicit intent: some Android builds present a multi-assistant chooser.
 * Public APIs cannot exactly impersonate SystemUI's privileged assist gesture.
 */
object AssistantLauncher {
    private const val TAG = "HotwordAssistant"

    private fun selected(context: Context): Pair<String, String?>? {
        val resolver = context.contentResolver
        for (key in listOf("assistant", "voice_interaction_service")) {
            val raw = runCatching { Settings.Secure.getString(resolver, key) }.getOrNull()
            val pkg = AssistantSelection.fromSetting(raw) ?: continue
            return pkg to ComponentName.unflattenFromString(raw ?: "")?.className
        }
        // Accept resolveActivity only when it reports a concrete default.
        @Suppress("DEPRECATION")
        val info = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_ASSIST), PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo ?: return null
        if (info.packageName == "android" ||
            info.packageName == "com.android.systemui" ||
            AssistantSelection.isResolver(info.name)) return null
        return (AssistantSelection.fromSetting(info.packageName) ?: return null) to info.name
    }

    private fun explicitIntent(context: Context): Intent? {
        val (pkg, preferredClass) = selected(context) ?: run {
            Log.w(TAG, "No unambiguous default assistant is available")
            return null
        }
        // First try the selected assistant's exported ACTION_ASSIST handler.
        // ACTION_VOICE_COMMAND is only a fallback *within the same package*.
        for (action in listOf(Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND)) {
            @Suppress("DEPRECATION")
            val handlers = context.packageManager.queryIntentActivities(
                Intent(action).setPackage(pkg), PackageManager.MATCH_DEFAULT_ONLY
            ).mapNotNull { it.activityInfo }
                .filter { it.exported && it.enabled && it.packageName == pkg }
                .distinctBy { it.name }
            val handler = handlers.firstOrNull { it.name == preferredClass }
                ?: handlers.singleOrNull()
                ?: continue
            Log.i(TAG, "Launching selected assistant via " + action + " in " + pkg)
            return Intent(action).apply {
                component = ComponentName(handler.packageName, handler.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (action == Intent.ACTION_ASSIST) {
                    putExtra(Intent.EXTRA_ASSIST_INPUT_HINT_KEYBOARD, false)
                }
            }
        }
        Log.w(TAG, "Selected assistant has no unique exported handler: " + pkg)
        return null
    }

    fun launch(context: Context): Boolean {
        val intent = explicitIntent(context) ?: return false
        return runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "Assistant launch rejected", it) }.isSuccess
        // Android may still silently block background launches.
    }

    fun notificationAction(context: Context): PendingIntent? {
        val intent = explicitIntent(context) ?: return null
        return PendingIntent.getActivity(
            context, 9, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
