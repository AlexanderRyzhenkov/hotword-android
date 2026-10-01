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

/** Public Android settings and signals only; no OEM-specific package names. */
object DeviceSetup {
    fun microphoneGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notificationsEnabled(context: Context): Boolean =
        notificationsGranted(context) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /** Special access explicitly granted by the user in Android Settings. */
    fun overlayAllowed(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun batteryExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    fun backgroundRestricted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 28 &&
            context.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    fun appDetails(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )

    fun openAppSettings(context: Context) = openFirst(context, listOf(appDetails(context)))

    fun openNotificationSettings(context: Context) = openFirst(
        context,
        listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            appDetails(context)
        )
    )

    fun openOverlaySettings(context: Context) = openFirst(
        context,
        listOf(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.packageName)
            ),
            appDetails(context)
        )
    )

    fun openBatterySettings(context: Context) = openFirst(
        context,
        listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            appDetails(context)
        )
    )

    fun openBackgroundSettings(context: Context) = openFirst(
        context,
        listOf(
            appDetails(context),
            Intent(Settings.ACTION_SETTINGS)
        )
    )

    private fun openFirst(context: Context, intents: List<Intent>) {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
                // Not all settings pages exist on every Android build.
            }
        }
    }
}
