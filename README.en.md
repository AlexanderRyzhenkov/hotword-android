# Hotword Android

[Русский](README.md) · **English**

An open-source Android application that detects a **user-defined trigger phrase** locally and attempts to invoke **whichever voice assistant the user has selected in Android settings**. There are no hardcoded assistant vendors or application packages.

> **Experimental MVP.** Android may block an app from opening the selected assistant from the background, especially on a locked screen. Fully hands-free behavior is **not guaranteed**. Upon recognition, a fallback notification includes an **Open assistant** action.

## MVP features

- A configurable trigger phrase in Russian or US English (subject to the offline speech model's vocabulary).
- Local, offline microphone processing using [Vosk](https://alphacephei.com/vosk/): no audio recording or uploading.
- Standard Android `Intent.ACTION_ASSIST` invocation, independent of any assistant provider.
- An explicitly user-started microphone foreground service with a persistent notification, stop action, and a 20-second microphone release window after detection.
- Optional one-time download of a small Russian or English Vosk model directly from Vosk.
- GitHub Actions build workflow with a debug APK artifact upon successful build.

## Build and try it

1. Set your desired **default digital assistant** in Android settings.
2. Open the project with Android Studio (JDK 17, Android SDK 35, Gradle 8.13) or run `gradle :app:assembleDebug`. The MVP does not include a Gradle Wrapper; CI installs Gradle automatically.
3. Install the debug APK, tap **Test default assistant** and confirm your chosen assistant starts in the foreground.
4. Select the recognition language, set your phrase and **Download offline model**. A connection is required for this initial download only. Models take tens of MB and need extra extraction space.
5. Allow microphone access (notifications are recommended), then tap **Start listening**. Test recognition with the screen on, while another app is in the foreground, and finally with the screen locked.
6. If the assistant did not automatically open, use the detection notification's **Open assistant** action. Stop and restart listening after changing language or trigger phrase.

After a successful CI build, download the APK from **Actions → Android CI → Artifacts → hotword-debug-apk** and extract the ZIP.

## Known limitations

- Android 10+ places restrictions on background activity launches, **including launches from foreground services**. `ACTION_ASSIST` is not a bypass. HyperOS and locked-screen behavior require actual device testing; on some phones only the fallback notification will appear.
- `ACTION_ASSIST` requests the system-selected assistant, but does **not** guarantee that the assistant starts listening or works on a locked screen.
- The MVP uses continuous **automatic speech recognition (ASR)** with phrase matching rather than a dedicated low-power keyword detector. False positives, missed triggers, out-of-vocabulary words, and significant battery use are possible. Prefer long, distinctive phrases.
- Listening must be explicitly enabled. Android may prevent microphone service auto-start after reboot or forced termination. OEM battery management may kill the process.
- GitHub Actions successfully compiled the debug APK and ran unit tests on 2026-09-30. Background/locked-screen behavior on Xiaomi 14 is **not yet verified on a physical device**; this is a test prototype, not a production release.

## Architecture

```text
MainActivity ──> Preferences + ModelInstaller
     │
     └──> WakeWordService (microphone foreground service)
                     │
                     └──> WakeWordEngine (Vosk implementation)
                                │ triggered
                                ▼
                         release microphone
                                │
                                └──> AssistantLauncher (ACTION_ASSIST)
```

`WakeWordEngine` isolates recognition for future integration of more power-efficient models. `AssistantLauncher` isolates all Android assistant dispatch logic.

## Security and privacy

Microphone audio stays on device and is not written to disk. Internet access is only needed to download the chosen model. A persistent notification indicates microphone use and includes an explicit stop action. This app does not use Accessibility or bypass Android platform restrictions.

## Contributing and roadmap

Next steps: evaluate `VoiceInteractionService`, support additional languages and engines, measure false triggers and battery drain, and add device-level instrumentation tests. When reporting results, include Android/OS version, phone model, and chosen assistant. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Source code: [MIT](LICENSE). Vosk and the separately downloaded language models have their own licenses; verify each model's terms before distributing bundled builds.