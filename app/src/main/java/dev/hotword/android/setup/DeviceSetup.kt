package dev.hotword.android.setup

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Public Android signals only. HyperOS-specific autostart is not queryable. */
object DeviceSetup {
    fun microphoneGranted(context: Context) =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun notificationsGranted(context: Context) =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notificationsEnabled(context: Context) =
        notificationsGranted(context) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun batteryExempt(context: Context) =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    fun backgroundRestricted(context: Context) =
        Build.VERSION.SDK_INT >= 28 &&
            context.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null))

    fun openAppSettings(context: Context) = openFirst(context, listOf(appDetails(context)))

    fun openBatterySettings(context: Context) = openFirst(context, listOf(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails(context)
    ))

    fun openAutostartSettings(context: Context) = openFirst(context, listOf(
        // Proprietary Xiaomi/HyperOS page; fallback to app details on other devices.
        Intent().setClassName("com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        appDetails(context)
    ))

    private fun openFirst(context: Context, intents: List<Intent>) {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) { /* Unsupported OEM settings activity. */ }
        }
    }
}
