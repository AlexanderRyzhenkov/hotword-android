# Hotword Android

[Русский](README.md) · **English**

An experimental open-source Android app that recognizes a **user-defined Russian trigger phrase** offline and attempts to invoke **whichever digital assistant Android has selected by default**. It is not tied to Alice, Gemini, or another assistant vendor.

## Fixes in 0.3.1

## Fixes in 0.3.1

- The app now invokes **only an exported activity belonging to the assistant configured in Android**. It no longer sends ambiguous implicit intents that can trigger an all-assistants chooser on HyperOS. It tries the selected package's `ACTION_ASSIST` handler first and `ACTION_VOICE_COMMAND` only as a fallback within that same package. If no unambiguous public handler exists, it reports an error rather than launching a different assistant.
- The foreground service now requests `START_STICKY` recovery and holds renewable, bounded `PARTIAL_WAKE_LOCK` leases while active to help keep recognition responsive when the screen is off. **This may significantly increase battery usage.** Neither feature overrides Android or OEM force-stop/background restrictions.
- Xiaomi 14 screen-off detection was confirmed by a user on version 0.2. Version 0.3.1 still needs device retesting after the assistant-dispatch and service lifecycle changes.
- Non-privileged apps cannot reproduce SystemUI's privileged assistant gesture exactly. If the configured assistant has no publicly exported activity supporting the relevant intent, an identical voice UI cannot be guaranteed.
- **Reboot / force stop:** open the app once to resume. Android cannot silently start a microphone foreground service from a background `BOOT_COMPLETED` receiver.

### Troubleshooting

First use the in-app assistant test button to isolate dispatch problems from recognition. Then test with the app in the background and the screen locked. Filter Logcat by `HotwordAssistant` and `HotwordService` for diagnostics. A blocked assistant activity launch is different from a stopped microphone listener.


## Version 0.3 — automatic listening

- The Russian Vosk offline model is **bundled in the final APK**. On first opening, it is unpacked to private storage; no phone-side download or Internet permission is needed.
- Once the model is prepared and microphone permission granted, listening **starts automatically**. There are no Start/Stop controls. The phrase is saved after a 1.5-second input pause; only recognition reloads inside the existing foreground service without stopping it.
- Assistant dispatch uses an explicit `ACTION_ASSIST` activity belonging to the selected assistant, with a package-scoped `ACTION_VOICE_COMMAND` fallback. An implicit all-assistants chooser is intentionally never opened. Recognition pauses for about 20 seconds after a trigger.
- Microphone and notification permissions are checked. The app reports Android's exposed battery optimization and background restrictions, and links to battery settings and compatible Xiaomi/HyperOS autostart settings.
- The notification shade shows only a minimal persistent microphone-service notification. Android requires it for a long-running microphone foreground service. No per-detection debug notification is posted; error notifications appear only when necessary.
- On device reboot or app update, a notification (when allowed) reminds the user to open the app and resume listening. Android 14+ prohibits starting a microphone foreground service directly from `BOOT_COMPLETED`.

### First-time setup

1. Choose your default digital assistant in Android settings, and check that it accepts spoken commands.
2. Install the APK and open the app. Wait for the bundled model to unpack, grant microphone permission (and preferably notifications); listening starts without a button.
3. In battery settings, select **Unrestricted**. On HyperOS, open **Autostart** and allow the app to run in the background; also review its other vendor-specific restrictions.
4. Change the trigger phrase as needed; it is applied automatically after a brief typing pause.

**Important:** There is **no public Android API** to read or change HyperOS's proprietary autostart toggle. The shortcut opens the relevant settings screen when available, otherwise the app details screen. Standard Android battery checks do **not** prove Xiaomi's additional restrictions are disabled.

### Android platform limitations

This is an ordinary, non-privileged app. `ACTION_VOICE_COMMAND` and `ACTION_ASSIST` are public assistant entry points, but **neither guarantees** reproducing the exact SystemUI assist gesture or starting a voice session. Android's background activity launch and lock-screen restrictions may block the automatic assistant launch; the selected assistant itself determines its behavior. An error notification provides a user-initiated fallback when the explicit launch fails.

**Truly unconditional always-on behavior is impossible to guarantee** for a third-party foreground microphone app: Android or the device vendor may stop the process, revoke microphone access, disable the microphone, or reboot. Android 14+ forbids a microphone foreground service started by a `BOOT_COMPLETED` receiver, regardless of Xiaomi's autostart setting. After a reboot the user must open the app at least once; a notification reminds them, if delivery is allowed.

Continuous Vosk ASR is relatively power-hungry and may miss uncommon custom words or produce false triggers. A dedicated low-power detector can replace it later via the isolated `WakeWordEngine` interface.

## Version 0.3.2: background dispatch and quicker rearming

On Android 10+, starting another application's Activity from a foreground microphone service may be **silently blocked** when Hotword is not visible, even if the hotword was recognized. A CPU wake lock keeps recognition running but **does not grant background activity launch privileges**. The configured default assistant remains unchanged.

An optional experiment to allow automatic dispatch while other apps are visible:

1. Open Hotword and explicitly grant the special **Display over other apps** permission in its settings screen. The app never enables this permission itself.
2. While recognition is active, a **small visible status dot** appears along the screen edge. It does not intercept touches or inspect window content. Android 15 may require a genuinely visible overlay, not just the permission, for background launches.
3. Retest on Home and within other apps. **The lock screen may cover application overlays**, so truly hands-free locked-screen activation cannot yet be guaranteed on all Android/HyperOS versions. A system-privileged assistant entry point or another approach may be necessary.

Previous versions destroyed/reloaded Vosk and waited a **fixed 20 seconds** after each trigger. The model is now retained in memory: only capture is temporarily paused. The service checks available Android recording-status information and normally resumes after about **3 seconds if no other app begins recording**, or **0.8 seconds after external recording stops**, with a 30-second fail-safe. Some devices conceal other apps' recording status, so the fallback may occasionally contend for the microphone.

A collapsible **Local diagnostics** section records only lifecycle events, including phrase detection, launch requests, and microphone rearming. Use **Copy recent events** to distinguish a missed wake word from a launch blocked by Android. No audio or recognized text is stored. The mandatory microphone foreground notification stays minimal.

**Note:** opt out of overlay access if you do not want a small always-visible status dot over other apps. Without it, Android may block dispatch while Hotword is in the background. Xiaomi 14 lock-screen behavior requires fresh device testing.

## Hotfix 0.3.3: stability

Version 0.3.2 created a real `TYPE_APPLICATION_OVERLAY` window. That window is unnecessary for this use case and may crash on some OEM builds while creating the window context or overlay. Version 0.3.3 **removes the overlay window entirely**.

Android's **Display over other apps** special access remains only as an explicit user-granted permission for background Activity launch eligibility. Android documents `SYSTEM_ALERT_WINDOW` itself as a background-activity-launch exception. The Android 15 rule that additionally requires a *visible overlay window* applies to **starting a foreground service from the background**; Hotword starts its microphone foreground service while its setup Activity is visible.

If the process still crashes, the app now stores only the exception type and the top stack frame in **Local diagnostics**. Reopen the app and use **Copy recent events**. Audio, recognized phrases, and other-app content are never written to diagnostics.

The quicker rearming from 0.3.2 remains: Vosk stays loaded and only microphone capture is temporarily released while the selected assistant is using it.

## Building

Requires JDK 17, Android SDK 35, and Gradle 8.13. To keep Git lightweight and manage third-party assets, the Russian model ZIP is **not committed**; a build-time script downloads a pinned ZIP, verifies SHA-256, and packages it as an APK asset **on the build machine**. The resulting APK works fully offline.

```sh
bash scripts/prepare-bundled-model.sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

For offline builds, put the official `vosk-model-small-ru-0.22.zip` in `app/src/main/assets/models/ru.zip`. Its SHA-256 is pinned in `models/ru.sha256` and Gradle verifies it. CI also verifies the bundled asset inside the built APK. Retrieve the debug APK under **Actions → Android CI → Artifacts → hotword-debug-apk**.

## Architecture

`MainActivity` handles setup and automatic start; `DeviceSetup` checks public Android settings; `ModelInstaller` unpacks the asset locally; `WakeWordService` owns the microphone and limited recovery; `WakeWordEngine` isolates recognition; `AssistantLauncher` dispatches without vendor hardcoding; `BootReceiver` reminds the user after reboot.

## Privacy and contributions

Audio is processed locally and is not stored; the app does not request `INTERNET`. Listening starts after microphone permission is granted, with Android's required visible microphone indicator. Users can revoke the microphone permission or force-stop the app from Android settings to stop listening. See [CONTRIBUTING.md](CONTRIBUTING.md). Source code is [MIT](LICENSE). The bundled Russian Vosk model is Apache 2.0; Vosk and other libraries have their own licenses.