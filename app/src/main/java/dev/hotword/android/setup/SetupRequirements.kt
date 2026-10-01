package dev.hotword.android.setup

import android.content.Context
import android.os.Build

/**
 * All capabilities required for reliable always-listening behavior.
 *
 * We intentionally separate Android runtime permissions from special system
 * access. OEM-only autostart switches are not treated as universal Android API.
 */
object SetupRequirements {
    enum class Requirement {
        MICROPHONE,
        NOTIFICATIONS,
        BACKGROUND_LAUNCH,
        BATTERY_UNRESTRICTED,
        BACKGROUND_NOT_RESTRICTED
    }

    fun missing(context: Context): Set<Requirement> = buildSet {
        if (!DeviceSetup.microphoneGranted(context)) add(Requirement.MICROPHONE)
        if (Build.VERSION.SDK_INT >= 33 && !DeviceSetup.notificationsGranted(context)) {
            add(Requirement.NOTIFICATIONS)
        }
        if (!DeviceSetup.overlayAllowed(context)) add(Requirement.BACKGROUND_LAUNCH)
        if (!DeviceSetup.batteryExempt(context)) add(Requirement.BATTERY_UNRESTRICTED)
        if (DeviceSetup.backgroundRestricted(context)) add(Requirement.BACKGROUND_NOT_RESTRICTED)
    }

    fun ready(context: Context): Boolean = missing(context).isEmpty()
}
