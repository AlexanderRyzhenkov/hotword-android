package dev.hotword.android.diagnostics

/** Process-local UI visibility for interpreting assistant dispatch logs. */
object AppVisibility {
    @Volatile var isActivityResumed: Boolean = false
}
