# Hotword Android

[Русский](README.md) · **English**

An experimental open-source Android app that recognizes a **user-configurable Russian trigger phrase** entirely on-device and attempts to open **whichever digital assistant is selected in Android settings**. It is not tied to Alice, Google, or any other assistant provider.

> **Experimental MVP:** Android may block assistant launches from a background app, particularly on a locked screen. A notification supplies a manual fallback. Fully hands-free operation on specific devices has not yet been verified.

## Features

- A **Russian Vosk offline model bundled in the APK**. No download, import, or internet connection is necessary on the user's phone. At first launch the included ZIP is extracted into private app storage before listening is enabled.
- Any user-configurable Russian trigger phrase (subject to Vosk model vocabulary and recognition quality). Restart listening to apply a changed phrase.
- Fully local microphone processing. Audio is not saved or uploaded, and the application **does not request INTERNET permission**.
- Vendor-neutral `Intent.ACTION_ASSIST` to invoke the Android-selected default assistant.
- User-started microphone foreground service with a persistent notification and a Stop action; it releases the microphone for 20 seconds when the phrase is detected.
- An insets-aware UI that avoids overlapping the status bar, camera cutouts and keyboard, including Android 15 / HyperOS edge-to-edge.
- GitHub Actions unit tests and APK build; CI independently confirms that the **pinned SHA-256 model is embedded in the produced APK**.

## Install and test

1. Set your preferred default digital assistant in Android settings.
2. Under **Actions → Android CI**, download the `hotword-debug-apk` artifact of a successful build. Unzip and install its APK.
3. Launch the app and wait for the **local extraction** of the included model; free internal storage is required. The phone does not download anything.
4. Enter a Russian phrase and tap **Test default assistant**. Grant microphone permission and tap **Start listening**.
5. Test with this app foregrounded, another app in front, and the screen off. Use the fallback notification if Android blocks automatic launches.

Start the service manually after reboot. Stop and restart listening to apply a new phrase.

## Build from source

Requirements: JDK 17, Android SDK Platform 35, Gradle 8.13. For repository size and third-party artifact management, the approximately 45 MB Russian model ZIP is **not committed to Git**. The following command retrieves it **on the build machine**, verifies a pinned SHA-256 and places it into the Android assets before packaging:

```sh
bash scripts/prepare-bundled-model.sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

For an offline build machine, manually place the official `vosk-model-small-ru-0.22.zip` at `app/src/main/assets/models/ru.zip`; its checksum must match `models/ru.sha256`. Gradle rejects absent or invalid model assets. CI prepares and verifies the model automatically, then verifies the asset **inside the final APK**. Only the build machine needs the archive download; the distributed app is fully offline.

## Limitations

- `ACTION_ASSIST` cannot bypass Android background Activity launch restrictions, and the selected assistant may not start listening while the phone is locked. The manual notification fallback is not hands-free.
- **Russian recognition only** for this MVP. Phrase customization does not imply multilingual recognition.
- Continuous Vosk ASR is not low-power keyword spotting. Battery use, missed triggers and false positives need field testing.
- First run needs free storage for local extraction. Xiaomi 14 / HyperOS locked-screen compatibility still needs device testing.

## Architecture

```text
Bundled APK asset (models/ru.zip) → ModelInstaller → Private storage
                                                      ↓
MainActivity → Preferences → WakeWordService → WakeWordEngine (Vosk)
                                               ↓ trigger
                                         release microphone
                                               ↓
                                    AssistantLauncher (ACTION_ASSIST)
```

`WakeWordEngine` isolates recognition for future low-power engine alternatives. `ModelLanguage` provides an extensible language-model registry, currently with a single Russian entry. `AssistantLauncher` is vendor-agnostic.

## Contributing and licensing

See [CONTRIBUTING.md](CONTRIBUTING.md) for contributions and device reports. Source code is [MIT](LICENSE). The embedded `vosk-model-small-ru-0.22` model is listed under **Apache 2.0** in the [Vosk model catalog](https://github.com/alphacep/vosk-space/blob/master/models.md). Vosk and other dependencies have their own licenses; distributors must observe applicable third-party terms.
