# J.A.R.V.I.S. Android Assistant

Kotlin Android client for a voice assistant using a Cloudflare Worker backend and Gemini APIs. The Gemini API key is held by the Worker and is not included in this repository.

## Project contents

- `app/` — Android application source and resources.
- `gradle/` and Gradle wrapper — project build configuration.
- `app/src/main/java/com/surya/jarvis/LiveVoiceManager.kt` — Live API WebSocket/audio manager.

## Recent Live API fix

`LiveVoiceManager` handles both text and binary WebSocket frames. A setup-only test against the configured Worker and Google Live endpoint returned `setupComplete` as a binary JSON frame; handling that callback allows the app to recognize successful session setup.

## Open in AndroidIDE

Use AndroidIDE's **Clone Git repo** option with:

```text
https://github.com/suryawebstudio/jarvis-android-assistant.git
```

This repository is private, so sign in to GitHub in AndroidIDE or otherwise provide GitHub authentication when prompted. After cloning, open/sync the Gradle project and build a debug APK. On a terminal in the project root, use:

```sh
bash ./gradlew assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Notes

- Do not add Gemini API keys, release keystores, passwords, or `release.properties` to Git.
- The Worker source is managed separately in Cloudflare and is not included here.
- Full microphone capture and speaker playback still require testing on the target Android device.
