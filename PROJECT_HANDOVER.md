# J.A.R.V.I.S. Android Assistant — Project Handover

**Last updated:** 2026-11-12  
**Repository:** [suryawebstudio/jarvis-android-assistant](https://github.com/suryawebstudio/jarvis-android-assistant)  
**Default branch:** `main`  
**Status:** Live voice conversation is working on the user's Android phone; the initial limited phone-control tools have been implemented in code and still need end-to-end device testing.

## What this project is

J.A.R.V.I.S. is a personal Android AI voice assistant inspired by the conversational assistant in Iron Man. The project is being built by the user on an Android phone with Code on the Go / AndroidIDE tooling, using Kotlin and low-cost or free-tier services where practical.

The immediate goal is dependable two-way voice conversation: the phone streams microphone audio to Gemini Live, receives generated speech, and plays it. The long-term goal is a useful phone assistant that can perform carefully scoped actions when the user asks, while remaining secure, transparent, and respectful of Android permissions.

It is not yet a movie-style unrestricted controller. Android deliberately limits background microphone use, app visibility, sensitive permissions, and silent control of other apps. Actions that send messages, place calls, make purchases, delete content, change security settings, or access sensitive information should require explicit user confirmation and appropriate permissions.

## Project architecture

### Android client

- **Language and framework:** Kotlin, Android SDK, native Android app, ViewBinding.
- **Application ID/package:** `com.surya.jarvis`.
- **Main UI:** `app/src/main/java/com/surya/jarvis/MainActivity.kt` and `app/src/main/res/layout/activity_main.xml`.
- **Voice/session/audio code:** `app/src/main/java/com/surya/jarvis/LiveVoiceManager.kt`.
- **Networking:** OkHttp 4.12.0 for the token request and Gemini Live WebSocket.
- **Microphone:** `AudioRecord`, mono PCM 16-bit at 16 kHz.
- **Speaker output:** `AudioTrack`, mono PCM 16-bit at 24 kHz, routed as Android media audio.
- **Device used for development/testing:** Infinix phone running Android 13; development is performed directly on the phone.

### Cloudflare Worker backend

- **Worker name:** `jarvis-ai-backend`.
- **URL:** `https://jarvis-ai-backend.suryawebstudio.workers.dev`.
- **Normal text chat:** The Android client sends a message to the Worker, which calls Gemini and returns a text reply.
- **Live-token route:** `POST /live-token` requests a short-lived Gemini Live API token using the Cloudflare Worker secret `GEMINI_API_KEY`.
- **Security boundary:** The long-lived Gemini API key belongs in the Cloudflare secret binding only. It must not be put in Kotlin source, the APK, or Git.
- **Repository boundary:** The Cloudflare Worker source is managed separately in Cloudflare and is not included in this Android repository.

### Gemini Live connection

The Android app requests the ephemeral token from the Worker, then connects to Google's constrained Live WebSocket endpoint using the documented token authentication. The app's current model is `models/gemini-3.1-flash-live-preview`, which matches the model label returned by the Worker. The Worker's normal chat route uses a separate Gemini text model.

The app sends its session setup first. After setup completes, Android streams PCM microphone audio. Gemini Live returns audio chunks and may send function calls for supported phone actions. The app handles both text and binary WebSocket frames because the server can return JSON messages in either form.

## Work completed

### Live setup acknowledgement diagnosis and fix

The initial supplied ZIP was incomplete, but a later upload contained the real Android source. A setup-only test using a short-lived Worker-issued token connected to Google and sent the app's setup message. Google's server returned `{"setupComplete": {}}` as a **binary** WebSocket frame.

The original listener only processed text frames, so it missed this acknowledgement and never started microphone capture. `LiveVoiceManager.kt` now handles both OkHttp callback overloads and routes binary UTF-8 JSON through the same parser. The setup-only test verified the handshake and setup acknowledgement. It did not test the full microphone/speaker loop.

### Hide raw server diagnostics

The app initially displayed a `GOOGLE RESPONSE: ...` diagnostic in its status area. That exposed protocol messages such as session-resumption handles. The raw payload display was removed. User-facing errors are now generic, while useful states such as `JARVIS LIVE — LISTENING` remain visible.

### Media-volume routing

Playback was changed from `AudioAttributes.USAGE_VOICE_COMMUNICATION` to `AudioAttributes.USAGE_MEDIA`, and the app keeps `AudioManager.MODE_NORMAL`. This makes J.A.R.V.I.S. output use the ordinary media/music audio path rather than call-volume routing. The user should test the resulting volume on the phone.

### Interruptible playback

Blocking speaker writes could keep the WebSocket callback busy and delay processing Gemini's interruption event. Playback now uses a separate thread and a bounded queue. When the server reports an interruption, the app advances a playback generation, clears queued old audio, and flushes the `AudioTrack` buffer. This should cut off speech faster, but final latency still depends on microphone capture, server-side voice detection, network timing, and audio buffers. It has not yet been confirmed on the target phone.

### Limited phone-control tools

The Live session now declares and handles two allowlisted Gemini function tools:

1. **`launch_app(app_name)`** — opens a matching installed app by its visible launcher label.
2. **`open_settings_page(page)`** — opens one of these Android Settings screens: `general`, `wifi`, `bluetooth`, `display`, `sound`, `apps`, or `location`.

The Android manifest declares visibility for apps matching the launcher intent. It does **not** request `QUERY_ALL_PACKAGES`. The starter launch/settings code does not accept arbitrary intents from the model, change Settings values, or interact inside a launched app. Optional visible-screen interaction is isolated in the user-enabled Accessibility Service described below. Tool calls are sent back to Gemini through a function response so it can speak a follow-up.

The function declaration, Android dispatch, app lookup, and Settings-intent paths passed static checks. They have not yet been verified end-to-end on the user's phone. App labels can differ by device or language, and launching another app may put J.A.R.V.I.S. in the background where Android restricts microphone capture unless a suitable foreground-service design is added.

### Visible-screen Accessibility control

`JarvisAccessibilityService.kt` is now registered as an optional Android Accessibility Service. The user must enable it manually from Android Accessibility settings. When enabled, the Live session exposes `inspect_visible_screen` and `control_visible_screen` tools. The service can inspect accessible labels on the current foreground screen and perform a single visible action: tap/click a visible control, type into a visible editable field, scroll, or invoke Back/Home/recent apps. Sensitive-looking actions require the model to receive explicit user confirmation and repeat the tool call with `confirmed=true`. The service does not bypass passwords, biometrics, CAPTCHAs, permission dialogs, or security screens. This implementation still needs device testing across real apps; custom-drawn or restricted interfaces may expose no accessible controls.

The Live manager now coordinates these calls as a bounded multi-step task. It starts each complex workflow with `start_task`, tracks task steps, allows at most 24 phone-action tool steps per task, resets state when voice mode stops or fails, and exposes `cancel_task` for user stop/cancel/abort requests. The model is instructed to inspect the screen between navigation steps and continue until the requested workflow is complete. Sensitive actions remain blocked until the user explicitly confirms, after which the model may repeat the blocked call with `confirmed=true`.

When a sensitive action is blocked, `LiveVoiceManager` also speaks a local Android Text-to-Speech prompt: the user is told that the action may send, delete, purchase, call, or share something and must say yes to confirm or stop to cancel. This avoids depending solely on Gemini's generated response audio. The TTS engine is stopped and released with the voice manager.

### Background voice listening

Voice mode is owned by `JarvisVoiceForegroundService`, not `MainActivity`, so it can continue while another app is in the foreground. The service starts only from the visible JARVIS activity after microphone permission is granted, declares the Android microphone foreground-service type, shows an ongoing low-priority notification, and provides a notification STOP action. MainActivity receives private in-app broadcasts for status and recognized speech while it is visible, but leaving the activity does not release the voice manager. The user can stop listening from the JARVIS button or the notification. Android may still reclaim services under severe system pressure, and the persistent notification is intentionally required for microphone privacy.

### Voice file management

`JarvisFileManager.kt` uses Android's Storage Access Framework and a persisted user-selected document-tree URI. The Live tools `choose_storage_folder`, `list_files`, `create_text_file`, `edit_text_file`, and `delete_file` operate only in that approved folder and restrict names to one simple file/folder name rather than arbitrary paths. The folder picker is opened through MainActivity so Android returns the URI correctly. `delete_file` is always blocked until the exact target is spoken through local Text-to-Speech and the user explicitly confirms; only then may the model retry with `confirmed=true`. No `MANAGE_EXTERNAL_STORAGE` or broad hidden-storage access is requested.

### GitHub repository

The repository was created, initially private, then changed to public at the user's request. It is now:

```text
https://github.com/suryawebstudio/jarvis-android-assistant
```

The Android source and README are in `main`. IDE metadata, build outputs, release keystores, signing properties, and API secrets are excluded. The current project commits include the binary-frame fix, diagnostic removal, media routing, interruptible playback, and phone-control starter tools.

## Current capabilities and gaps

### Implemented in the current source

- Requests a short-lived Gemini Live token from Cloudflare.
- Connects to Gemini Live over WebSocket.
- Handles text and binary server messages.
- Streams microphone PCM audio and plays returned PCM audio.
- Routes output through Android's media audio stream.
- Processes server-reported interruption events by clearing queued playback.
- Supports the two narrow phone-control function calls listed above.
- Supports optional visible-screen control through the user-enabled Accessibility Service.
- Coordinates continuous multi-step workflows with a 24-step safety limit and cancellation tool.
- Keeps voice capture alive across other apps through a microphone foreground service and persistent stop notification.
- Creates and edits UTF-8 text files and lists/deletes entries inside a user-approved Storage Access Framework folder.
- Preserves a separate normal text-chat path through the Worker.

### Not complete or not yet verified

- **Phone-control end-to-end test:** Confirm voice commands actually result in app launches and supported Settings screens on the phone.
- **Accessibility end-to-end test:** Enable JARVIS under Android Accessibility settings, then test screen inspection, tapping, typing, scrolling, and confirmation-gated actions in several apps.
- **Multi-step task test:** Try “open WhatsApp, find John, type a message, and send it”; verify JARVIS inspects between steps, stops before Send, resumes only after confirmation, and cancels on “stop”.
- **Background listening test:** Start voice mode, accept notification permission, open another app, speak a command, verify JARVIS responds, then stop from the notification and verify microphone capture ends.
- **File-management test:** Say “choose a storage folder”, select a folder, create and edit a text file, list it, then request deletion and verify JARVIS speaks the exact name and waits for “yes” before deleting.
- **Barge-in latency test:** Confirm interruption stops speech promptly on the device. Current local code reacts to the server's interruption event; local voice activity detection could stop playback even earlier.
- **Build verification in a full Android environment:** The cloud sandbox did not have a usable Java compiler/Android SDK, so an Android APK build could not be completed there. Use Code on the Go on the phone to build.
- **Visible live transcript/history:** `MainActivity` has a callback prepared for recognized speech, but `LiveVoiceManager` does not yet deliver transcription events to it. Voice turns therefore are not fully shown as chat history.
- **Conversation persistence/context:** The Live API session maintains its current conversation, but there is no durable local transcript/history implementation. The normal chat UI currently sends the new message without appending its full local history to the request.
- **Background voice reliability:** No foreground microphone service or robust lifecycle/reconnect design is implemented yet.
- **Permissions/confirmation UX:** The current starter tools avoid sensitive actions, but a general confirmation UI for future consequential actions is not yet built.

## Planned roadmap

### Phase 1 — Verify the current phone build

1. Pull the latest `main` branch in Code on the Go, or replace the two relevant files manually.
2. Build and install the debug APK on the Infinix Android 13 phone.
3. Test ordinary voice conversation, media volume, app launching, and each supported Settings page.
4. Test interruption during J.A.R.V.I.S. speech and note how many milliseconds or words continue after speaking over it.
5. Record exact on-screen errors/status text and device behavior; avoid logging or sharing tokens and secrets.

### Phase 2 — Improve interaction reliability

1. Add user-friendly matching for installed app names and report when a requested app is ambiguous or missing.
2. Add voice-input and model-output transcripts to the chat history, using Live API transcription events.
3. Add conversation history persistence and send relevant context to normal chat safely.
4. Make voice-session UI state recover cleanly after errors, interruption, app launch, app backgrounding, and return to J.A.R.V.I.S.
5. Tune playback buffering and, if needed, add local voice activity detection so local playback can stop as soon as the user starts speaking rather than waiting for the server's event.

### Phase 3 — Add more carefully scoped actions

Only after the initial tools are reliable, consider more low-risk actions such as creating an alarm/timer through Android Clock intents, toggling the flashlight with an explicit action, or basic media controls. Keep each action as a declared tool with a narrowly validated argument set. Ask the user to approve sensitive capabilities before adding them.

### Phase 4 — Add confirmation and permission workflows

For actions such as calls, messages, calendar changes, sharing personal data, purchases, deleting content, or modifying sensitive device settings, show a clear confirmation in the app before execution. Request only the Android permissions needed for a selected feature. Do not let the model silently grant permissions or bypass Android's own confirmation screens.

### Phase 5 — Production hardening

Improve lifecycle handling, foreground-service behavior for any intended background listening, WebSocket reconnection/session resumption, timeout/error diagnostics, privacy controls, and tests. Keep the API key on Cloudflare. Do not add broad Accessibility or device-administration control unless there is a specific user-approved need and a careful security design.

## Developer workflow on Code on the Go

Repository clone URL:

```text
https://github.com/suryawebstudio/jarvis-android-assistant.git
```

The repository is public. From the project root (the directory containing `settings.gradle.kts`), update and build with:

```sh
git pull origin main
bash ./gradlew assembleDebug
```

If Git complains that the shared-storage repository has dubious ownership, mark only this project folder safe:

```sh
git config --global --add safe.directory "$PWD"
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Keep backups before replacing files manually. Do not run destructive Git commands such as `git reset --hard` to resolve an ordinary pull conflict without first checking the local changes.

## Official technical references

- [Gemini Live API tools and function calling](https://ai.google.dev/gemini-api/docs/live-api/tools)
- [Gemini Live API WebSocket reference](https://ai.google.dev/api/live)
- [Android package visibility guidance](https://developer.android.com/training/package-visibility/declaring)
- [Android Settings intents reference](https://developer.android.com/reference/android/provider/Settings)
