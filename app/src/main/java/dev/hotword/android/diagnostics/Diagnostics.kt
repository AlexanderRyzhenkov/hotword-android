package dev.hotword.android.diagnostics

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal local lifecycle diagnostics. Never records audio, text hypotheses,
 * assistant conversations or personal data. Limited to 30 lifecycle events.
 */
object Diagnostics {
    private const val TAG = "Hotword"
    private const val PREFS = "hotword_diagnostics"
    private const val KEY = "recent_events"
    private const val MAX_LINES = 30

    @Synchronized
    fun record(context: Context, event: String) {
        Log.i(TAG, event)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        val previous = prefs.getString(KEY, "").orEmpty().lineSequence().filter { it.isNotBlank() }
        prefs.edit().putString(KEY,
            (previous + "$timestamp · $event").toList().takeLast(MAX_LINES).joinToString("\n")
        ).apply()
    }

    fun recent(context: Context): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY, "").orEmpty()
}
