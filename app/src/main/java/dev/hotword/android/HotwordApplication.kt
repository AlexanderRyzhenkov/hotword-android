package dev.hotword.android

import android.app.Application
import dev.hotword.android.diagnostics.Diagnostics

/**
 * Stores only a tiny local crash marker (exception class + top frame) so the
 * next test run can tell whether the process crashed or was killed by Android.
 */
class HotwordApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val top = error.stackTrace.firstOrNull()
                val location = if (top != null) " at ${top.className}.${top.methodName}:${top.lineNumber}" else ""
                Diagnostics.record(this, "CRASH ${error.javaClass.simpleName}$location")
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
