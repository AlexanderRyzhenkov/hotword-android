# Hotword Android

[Русский](README.md) · **English**

An experimental open-source Android app that recognizes a **user-defined Russian trigger phrase** offline and attempts to invoke **whichever digital assistant Android has selected by default**. It is not tied to Alice, Gemini, or another assistant vendor.

## Version 0.3 — automatic listening

- The Russian Vosk offline model is **bundled in the final APK**. On first opening, it is unpacked to private storage; no phone-side download or Internet permission is needed.
- Once the model is prepared and microphone permission granted, listening **starts automatically**. There are no Start/Stop controls. The phrase is saved after a 1.5-second input pause, followed by a short service restart.
- The app uses `ACTION_VOICE_COMMAND` **only when its handler belongs to the system-selected assistant**; otherwise it falls back to `ACTION_ASSIST`. After a detected phrase, our microphone is released for about 20 seconds so the assistant can listen.
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