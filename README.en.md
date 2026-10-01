# Hotword

[Русский](README.md) · **English**

Hotword is an open-source Android app that recognizes a user-configurable Russian trigger phrase locally and invokes **the digital assistant selected as Android's default**. It is not tied to a specific assistant provider.

## Features

- The Russian Vosk offline model is bundled in the final APK. Recognition does not require internet access on the phone.
- The trigger phrase is user-configurable and applied automatically.
- Listening runs as a microphone foreground service without Start/Stop controls.
- An optional short two-tone acknowledgement plays after a successful trigger; it is enabled by default.
- Only the assistant configured as Android's default is targeted. Hotword never opens an ambiguous multi-assistant chooser.
- All required Android permissions and system settings are checked before continuous listening starts.
- Required access is rechecked while the service is running; Hotword warns when an important setting is revoked or changed.
- Local diagnostics contain lifecycle events only. Audio and recognized speech text are not stored.

## Initial setup

Continuous listening starts only when all required items are ready:

1. **Microphone** — required for local recognition.
2. **Notifications** — used for the foreground-service notification and setup/error alerts.
3. **Background assistant launch** — Android's Display over other apps special access. Hotword does not create overlay windows; the permission is used for background launch eligibility.
4. **Unrestricted battery use** — reduces the chance of long-running recognition being suspended by the OS.
5. **Background activity allowed** — Android must not report the app as background-restricted.

Each item is shown in the app. Returning from system settings triggers an automatic recheck. If required access later changes, Hotword records the event and posts a warning when notifications are still available.

> Settings pages vary across Android devices. Hotword uses public Android APIs and standard settings Intents rather than vendor-specific packages.

## Trigger acknowledgement sound

After the trigger phrase is recognized, Hotword can play a short descending “tu-dum” cue before handing the microphone to the assistant. The setting can be disabled from the main screen.

For UX research, open notification-sound collections were reviewed, including [akx/Notifications](https://github.com/akx/Notifications), which is available under CC0 among its license options. **No third-party audio recording is bundled.** The final Hotword cue is synthesized in code from two decaying tones, so it does not require a separate sound-asset license.

## Icon

The new original Hotword mark combines a vertical audio waveform with a response arc on a dark background. Standard, round, adaptive, and monochrome launcher variants are included, and the same waveform mark is used for service notifications.

## Privacy

- Audio is processed locally.
- Audio is not saved.
- Audio and recognized text are not uploaded.
- The app does not request the `INTERNET` permission.
- Local diagnostics store only technical lifecycle events such as service start, trigger detection, and errors.

## Android limitations

Hotword is a regular third-party app, not a privileged Android system component. Assistant invocation uses public Android intents and therefore depends on an exported entry point being available from the selected assistant.

On Android 14+, a regular app cannot start a microphone foreground service directly from `BOOT_COMPLETED`. After a full device reboot, the user must open Hotword once before continuous microphone listening can resume.

A foreground service requires an ongoing system notification. On Android 13+, `POST_NOTIFICATIONS` is not technically required to create the service, but Hotword intentionally treats notification delivery as required setup so the user can see long-running activity and setup warnings.

## Building

Requirements: JDK 17, Android SDK 35, Gradle 8.13.

```sh
bash scripts/prepare-bundled-model.sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

The Russian model archive is not committed to Git. The build script downloads the pinned archive, verifies SHA-256, and packages it into the APK. CI verifies the same model inside the resulting APK.

## Architecture

```text
MainActivity
 ├─ SetupRequirements / DeviceSetup
 ├─ Preferences
 └─ WakeWordService
      ├─ VoskWakeWordEngine
      ├─ TriggerChime
      ├─ AssistantLauncher
      └─ Diagnostics
```

`WakeWordEngine` remains replaceable so continuous Vosk ASR can later be replaced by a dedicated low-power keyword spotter.

## License

Project source is distributed under the [MIT License](LICENSE). The embedded Vosk model and third-party libraries are distributed under their respective licenses.
