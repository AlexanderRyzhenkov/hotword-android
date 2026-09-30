package dev.hotword.android.assistant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Uses Android's chosen default assistant. No vendor package names/deep links. */
object AssistantLauncher {
    private fun intent(): Intent = Intent(Intent.ACTION_ASSIST).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun launch(context: Context): Boolean = try {
        context.startActivity(intent())
        true // Background activity restrictions can silently block this launch.
    } catch (_: Exception) {
        false
    }

    fun notificationAction(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 9, intent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
