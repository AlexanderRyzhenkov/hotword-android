# Hotword

[Русский](README.md) · **English**

Hotword is an open-source Android app that locally recognizes a user-configurable Russian trigger phrase and invokes **the digital assistant selected as Android's default**. It is not tied to a specific assistant provider.

## Version 0.5.1 — PocketSphinx KWS tuning

Hotword no longer runs full Vosk speech transcription while idle. It now uses **PocketSphinx keyword spotting**, decoding only one configured trigger phrase. The purpose of this release is to substantially reduce continuous CPU and battery use.

The Russian CMUSphinx acoustic model is packaged into the APK at build time. The PocketSphinx Android binary is pinned to a specific upstream artifact. Nothing is downloaded on the phone and the app does not request `INTERNET`.

### User-configurable Russian phrase

Changing the phrase still does not require model retraining.

Hotword normalizes Cyrillic input and builds a tiny PocketSphinx pronunciation dictionary on-device for the current phrase. Starting with 0.5.1, known words use pronunciations from the official `ru.dic` shipped in the `cmusphinx-ru-5.2` package. The full lexicon stays compressed in the APK and is streamed only when recognition starts or the phrase changes; PocketSphinx itself receives only the entries required by the current phrase.

Words missing from the official lexicon still use the lightweight fallback G2P with alternate stress positions. This preserves arbitrary Russian phrases without model retraining while avoiding unnecessary stress variants for common words.

```text
"привет помощник"
        ↓
official ru.dic → exact pronunciations
        ↓
fallback G2P for unknown words only
        ↓
PocketSphinx single-keyphrase search
```

In 0.5.0 the trigger phrase must contain Russian words. Punctuation and hyphens act as separators.

## Features

- Offline wake-phrase recognition.
- User-configurable Russian phrase without retraining.
- Invokes only Android's selected default assistant.
- Background and screen-off listening through a microphone foreground service.
- Optional short acknowledgement chime after detection.
- Required Android access is checked before listening starts and rechecked while running.
- Local lifecycle diagnostics without storing audio or recognized speech.

## Battery experiment

The previous continuous-Vosk build showed high background usage on a real device: roughly 22% of the system battery-usage share during the observed period, 1 h 38 min CPU time, and almost 8 hours of active time. PocketSphinx 0.5.x is the direct comparison line for battery measurements.

For a useful comparison, record battery share, CPU time, active time, missed triggers, and false activations over a similar several-hour idle period.

The existing `PARTIAL_WAKE_LOCK` is intentionally unchanged in this release so the recognizer is the main variable being tested. Deep-sleep behavior can be optimized separately.

## Privacy

Audio is processed locally and is not stored or uploaded. The app does not request the `INTERNET` permission. Diagnostics contain technical lifecycle events only.

## Limitations

PocketSphinx uses a classic HMM/GMM recognizer. Keyword accuracy remains phrase-dependent. Version 0.5.1 chooses the KWS threshold from phrase length: short one-word phrases use a stricter threshold to suppress false activations, while multiword phrases use a more sensitive threshold. This follows CMUSphinx guidance that keyword thresholds should be tuned per keyword.

Wake phrases of 2–4 words are preferred. Very short single-word phrases such as “Алиса” remain inherently more prone to false activations even with a stricter threshold.

After a full reboot, Android 14+ requires Hotword to be opened once before continuous microphone foreground-service listening can resume.

## Building

Requires JDK 17, Android SDK 35, and Gradle 8.13.

```sh
bash scripts/prepare-pocketsphinx.sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

The build script fetches a pinned PocketSphinx Android AAR and the official CMUSphinx Russian acoustic package, verifies pinned identifiers/checksum, and packages only the required acoustic files. Large generated build inputs are not committed to Git.

## Architecture

```text
WakeWordService
 ├─ PocketSphinxWakeWordEngine
 │    ├─ single-keyphrase search
 │    └─ RussianPronunciation (runtime G2P)
 ├─ TriggerChime
 ├─ AssistantLauncher
 └─ Diagnostics
```

`WakeWordEngine` remains the abstraction between the service and keyword detector.

## License

Hotword source is distributed under the [MIT License](LICENSE).

PocketSphinx Android is BSD-2-Clause licensed. The Russian acoustic model is sourced from the official CMUSphinx `cmusphinx-ru-5.2` package and retains the upstream package license. Third-party components retain their respective licenses.
