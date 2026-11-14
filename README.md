# M.A.R.K. Android Assistant

Kotlin Android client for a voice assistant using a Cloudflare Worker backend and Gemini APIs. The Gemini API key is held by the Worker and is not included in this repository.

See [`PROJECT_HANDOVER.md`](PROJECT_HANDOVER.md) for the architecture, work completed, testing status, limitations, and roadmap.

## Project contents

- `app/` — Android application source and resources.
- `gradle/` and Gradle wrapper — project build configuration.
- `app/src/main/java/com/surya/jarvis/LiveVoiceManager.kt` — Live API WebSocket/audio manager.
- `app/src/main/assets/jarvis-interactive-ui.html` — the touch-friendly MARK core UI, panels, chat composer, history, and settings controls.

## Interactive core UI

The main screen now loads the supplied interactive core UI locally in a WebView. Its rotating particle core, tap/drag/hold interactions, Settings/History/Chat panels, microphone control, message composers, particle/sound/voice/auto-listening toggles, full-storage access action, and Accessibility action are connected to native Android behavior through the restricted `AndroidJarvis` bridge. The WebView loads only the bundled local asset; external URL navigation and content access are disabled.

## Recent Live API fix

`LiveVoiceManager` handles both text and binary WebSocket frames. A setup-only test against the configured Worker and Google Live endpoint returned `setupComplete` as a binary JSON frame; handling that callback allows the app to recognize successful session setup.

## Limited phone control

The Live session can request two allowlisted actions: launch an installed app by its visible name, or open one of the supported Android Settings pages (general, Wi-Fi, Bluetooth, display, sound, apps, or location). These starter actions do not change settings or interact inside launched apps, and the app does not request `QUERY_ALL_PACKAGES`.

MARK now also supports direct flashlight on/off control when the user grants the Android camera permission; it uses only the flashlight API and never opens a camera preview. The Settings tool can open Quick Settings plus Wi-Fi, Bluetooth, wireless/network, NFC, display, sound, notifications, apps, battery, battery saver, location, security, privacy, accessibility, date/time, keyboard, storage, VPN, cast, and home-screen pages. Modern Android intentionally prevents ordinary apps from silently changing protected Wi-Fi, Bluetooth, mobile-data, and security settings, so MARK opens the relevant system panel and reports what actually happened. With the optional MARK Accessibility service enabled, it can operate visible controls on those screens after the user has opened them; it does not bypass Android confirmation or security screens.

## Visible-screen control

MARK now includes an optional `JarvisAccessibilityService`. After the user manually enables it in Android Accessibility settings, Gemini can inspect the currently visible accessible labels and perform one narrow action at a time: tap a visible control, type into a visible text field, scroll, or use Back/Home/recent apps. The service does not access hidden content or bypass passwords, biometrics, CAPTCHAs, permission dialogs, or security screens. Consequential actions such as sending, deleting, purchasing, calling, or sharing require explicit confirmation in the conversation.

The Live session also coordinates these calls as a bounded multi-step task. It starts each complex workflow with `start_task`, performs one action at a time, inspects the screen between navigation steps, stops after 24 tool steps to prevent loops, and exposes `cancel_task` for “stop”, “cancel”, or “abort” commands. A sensitive final action such as tapping Send is blocked until MARK asks for confirmation and the user explicitly agrees. The Android client also uses local Text-to-Speech to speak the confirmation request, so the prompt is audible even when the model's response audio is unavailable.

Voice mode now runs in `JarvisVoiceForegroundService`. After microphone permission and the Android notification permission are granted, pressing the voice button starts a persistent microphone foreground service with an ongoing notification and a STOP action. MARK continues listening while another app is in front. Stop it from the MARK button or the notification; Android may still stop background work under extreme system conditions, and Android requires the visible notification for microphone privacy.

## Voice file management

MARK can manage files only inside a folder that the user explicitly selects through Android's Storage Access Framework. Use voice commands such as “choose a storage folder”, “list my files”, “create a text file named notes.txt”, or “edit notes.txt”. It does not claim unrestricted access to the entire phone. Deletion is permanently confirmation-gated: MARK speaks the exact file or folder name and deletes it only after the user explicitly says yes and the tool call includes `confirmed=true`.

The app also supports an opt-in **Full Storage Access** toggle in the main settings area. The toggle first shows a warning and then opens Android's `MANAGE_EXTERNAL_STORAGE` screen; MARK uses full shared-storage mode only after both Android permission and the in-app toggle are enabled. Turning the toggle off returns to selected-folder mode. Full mode currently manages top-level entries under shared storage and restricts voice-created or edited content to small text files; deletion remains confirmation-gated.

## Persistent memory

MARK has a private on-device memory store backed by Android `SharedPreferences`, so saved memories remain available after the app is closed or restarted. Say or type **“remember that …”** to save a fact, or use common personal facts such as “my name is …”, “I live in …”, “I work as …”, and “my favorite … is …”. Use **“what do you remember”**, **“forget …”**, or **“forget all memories”** to inspect or remove memories. The Settings panel also shows the memory count and provides a button to clear all memories. Saved facts are included as private context for normal chat and live voice sessions; full conversation transcripts are not stored.

## Open in AndroidIDE

Use AndroidIDE's **Clone Git repo** option with:

```text
https://github.com/suryawebstudio/jarvis-android-assistant.git
```

This repository is public. After cloning, open/sync the Gradle project and build a debug APK. On a terminal in the project root, use:

```sh
bash ./gradlew assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Notes

- Do not add Gemini API keys, release keystores, passwords, or `release.properties` to Git.
- The Worker source is managed separately in Cloudflare and is not included here.
- Full microphone capture and speaker playback still require testing on the target Android device.
