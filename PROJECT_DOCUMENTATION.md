# Control X Project Documentation

This file is the maintained source of truth for Project Control X. It documents the implementation, architecture, Android constraints, security behavior, build state, and future work.

**Rule for future changes:** Every feature addition, behavior change, dependency change, permission change, Android/OEM limitation, or security decision must update this document and the relevant module section.

## 1. Project Overview

Control X is a native Kotlin Android voice and screen-control application. It is designed with two execution paths:

1. **Fast Path:** deterministic parsing and public Android APIs for low-latency commands.
2. **Agent Path:** accessibility-screen grounding and a bounded Groq ReAct loop for complex UI tasks.

The implementation is for modern non-rooted Android devices. It does not claim universal access to every app or setting, because Android sandboxing, secure screens, app policy, device policy, OEM process management, and Play platform rules may block actions.

## 2. Repository

- GitHub: `vivekoffical08-jpg/Control-X`
- Branch: `main`
- Language: Kotlin
- UI: Jetpack Compose
- Minimum SDK: API 26
- Compile SDK: API 35
- Initial Android target: API 30-34 behavior
- Intended first device validation: Infinix Note 12 Pro 5G, Android 12/13, XOS

## 3. Module Structure

```text
app/
  src/main/java/com/controlx/
    MainActivity.kt
    accessibility/
      ControlAccessibilityService.kt
      GestureExecutor.kt
      ScreenNodeParser.kt
    agent/
      GroqApiClient.kt
      ReActEngine.kt
    domain/
      ActionModels.kt
      ActionRecord.kt
      ControlXModels.kt
    input/
      AndroidVoiceInputEngine.kt
      VoiceInputEngine.kt
      VoiceInputManager.kt
    overlay/
      AssistantOverlayManager.kt
      OverlayController.kt
    platform/
      PermissionRepository.kt
      PermissionSnapshot.kt
    router/
      FastPathRouter.kt
    security/
      ExecutionGuard.kt
      SafetyGuardian.kt
      SensitiveActionPolicy.kt
```

## 4. Voice and Input

### Implemented

- `VoiceInputManager` wraps Android `SpeechRecognizer`.
- `EXTRA_PARTIAL_RESULTS` is enabled.
- Partial, final, and error callbacks are exposed.
- Listening can be stopped and speech-recognition resources destroyed.
- `VoiceInputEngine` and `AndroidVoiceInputEngine` provide a second input abstraction.

### Practical constraints

- Partial result latency depends on the device speech engine and OEM implementation.
- Android may route recognition to an on-device or network provider.
- Microphone permission is required.
- XOS process restrictions may stop background listening; a foreground service and persistent notification may be required for future uninterrupted listening.
- No wake-word engine is implemented yet.

## 5. Fast-Path Router

`FastPathRouter.route(command)` performs deterministic command handling without an LLM.

### Supported implementation areas

- Launch exact package names or installed app labels.
- Toggle flashlight/torch through `CameraManager.setTorchMode`.
- Adjust ring, music, or alarm volume through `AudioManager`.
- Open Wi-Fi settings panel or Bluetooth/display settings.
- Dispatch alarm and timer setup intents.
- Route Home through a public launcher intent.
- Return explicit errors for Back and Recents when no accessibility service is available.

### Android limitations

- Some settings panels may vary by Android version and OEM.
- Third-party apps cannot silently toggle every system setting.
- Airplane mode and protected system settings may require user interaction.
- Closing arbitrary apps silently is not guaranteed without a privileged API.
- Alarm and timer actions may still open the system app UI rather than being fully autonomous.

## 6. Screen Perception

`ScreenNodeParser.captureAndSanitize` traverses the active accessibility hierarchy and returns:

- `cx_1`, `cx_2`, and so on
- class name
- text
- content description
- resource ID
- bounds
- clickable/editable/scrollable flags
- sensitive flag

The parser maps ephemeral IDs back to current `AccessibilityNodeInfo` instances. Password nodes are redacted. PIN, OTP, CVV, password, and passcode text/labels are redacted.

### Sensitive node behavior

- Sensitive nodes use `[REDACTED]`.
- `isSensitive` is set to `true`.
- Agent text input is rejected for sensitive fields.
- Credential entry is not implemented.

## 7. Screen Actuator

`GestureExecutor` currently provides public accessibility APIs for:

- clicking a node or nearest clickable parent
- one-stroke coordinate tapping
- text injection through `ACTION_SET_TEXT`
- node scrolling and coordinate-drag fallback
- Back, Home, Recents, and Notifications global navigation

### Runtime behavior

- Nodes must belong to the current accessibility snapshot.
- Coordinate gestures require accessibility gesture support.
- Text input may fail for secure fields or custom widgets.
- Custom canvas games and native-rendered UI may not expose accessibility nodes.
- Gesture completion is reported through Android callbacks.

## 8. ReAct Agent Brain

`GroqApiClient` sends a strict JSON-object request to:

`https://api.groq.com/openai/v1/chat/completions`

Default model:

`llama-3.3-70b-versatile`

The request includes only the sanitized current snapshot and bounded action history. The response must use:

- `thought`
- `action`
- `targetNodeId`
- `coordinates`
- `textInput`
- `scrollDirection`
- `systemCommand`
- `expectedOutcome`

### Agent limits

- Maximum task steps: 10.
- Node targets must exist in the current snapshot.
- History and screen fingerprints detect repeated actions.
- Unknown action names are rejected.
- Sensitive nodes and credential/destructive instructions are blocked by policy.
- API keys must be supplied by the app configuration; they are not stored in source.
- Screen data must not be sent over the network unless the user explicitly enables the configured LLM path.

## 9. Safety and Kill Switch

The shared `ExecutionGuard` provides a stopped/allowed state.

- Accessibility key-event handling stops execution when Volume Down is pressed.
- Overlay bubble and assistant panel use the same shared guard.
- `SafetyGuardian` blocks sensitive packages and credential terms.
- Financial, purchase, transfer, and deletion language is classified as biometric approval.
- `SensitiveActionPolicy` provides command-level hard blocks.
- Password/PIN/OTP/CVV entry is not implemented.
- Data deletion and financial actions are not implemented.
- No arbitrary shell, root, hidden API, device-admin, or silent installation behavior is implemented.

### Sensitive package policy

The initial blacklist includes authenticator and credential-related package families. It must be expanded by package and app signature as real device testing identifies sensitive apps.

## 10. Permissions

### Runtime/user grant

- `RECORD_AUDIO`
- `POST_NOTIFICATIONS` on Android 13+
- `CAMERA` for flashlight and future vision features

### Special settings

- `SYSTEM_ALERT_WINDOW`
- `WRITE_SETTINGS`
- Notification Listener access
- Accessibility Service enablement
- Battery optimization exemption
- MediaProjection consent for future screen capture

The app must present exact reasons for each permission and handle denial, permanent denial, OEM settings, and settings activity results.

## 11. Overlay UI

The overlay implementation is permission-gated and uses `TYPE_APPLICATION_OVERLAY`.

- Bottom assistant surface occupies approximately 45% of the display.
- The top display remains available.
- A dark glass-style surface is used.
- API 31+ render effects provide a background blur where available.
- Live transcript and latency state can be updated.
- The panel can trigger the shared kill switch.
- A full neon execution aura and production soundwave visualizer are not yet implemented.

## 12. Android and OEM Constraints

- Android 11-14 package visibility and system APIs differ.
- Scoped storage limits arbitrary file access.
- Background speech recognition and accessibility persistence depend on OEM policy.
- XOS may aggressively stop background processes or require battery-exemption configuration.
- Overlay and accessibility enablement may require manual steps.
- Samsung, Xiaomi, OnePlus, Oppo, Vivo, Transsion, and Pixel variants differ.
- Work profiles and device owner policies may prohibit actions.
- Secure views and banking apps may deliberately hide accessibility content.
- Coordinate gesture behavior varies by launcher, animation state, and custom widgets.
- Android cannot guarantee a human-identical universal agent on every app.

## 13. Build and Test State

### Implemented

- Kotlin/Gradle project structure
- Compose app foundation
- Permission snapshot UI
- Unit test files for action policy, agent node grounding, and security policy
- Manifest and resource XML
- Public API module implementations

### Not verified in this environment

- Java/Gradle/Kotlin compilation
- Unit test execution
- Android instrumentation test execution
- Real device accessibility and gesture behavior
- OEM process persistence
- Voice latency targets
- Groq network behavior

Build verification must be performed in Android Studio or a host with JDK 17 and Android SDK 35.

## 14. Planned Updates

Future development should proceed in this order:

1. Instrumented permission onboarding.
2. Device smoke-test harness.
3. Live voice UI and wake-word integration.
4. Executable fast-path controller implementations.
5. Accessibility service snapshot integration.
6. Gesture executor integration and settle verification.
7. Groq API configuration and secure key storage.
8. ReAct loop state machine.
9. Overlay UI, soundwave visualizer, and execution aura.
10. Infinix XOS persistence testing.
11. Biometric approval UI for future explicitly allowed high-risk operations.

## 15. Change Log

### 2026-11-20 — Vertical Slice Integration

- Added real-time permission diagnostics for microphone, overlay, accessibility, and battery optimization.
- Added one-tap settings entry points and a permission-gated overlay launch button.
- Added a Compose text Fast-Path diagnostic panel.
- Added a bottom 45% assistant overlay with top 55% dimmed touch-passthrough scrim.
- Added overlay states for idle, listening, parsing, executing, and dismiss.
- Added live partial/final voice transcription callbacks into `FastPathRouter`.
- Added success, unhandled, and error settlement feedback.
- Added 800 ms success feedback before overlay collapse.
- Added typed text command input and close/abort controls.
- Added shared emergency kill observation from the overlay and accessibility service.
- Added numeric media-volume setting support for commands such as "set volume to 80".

### 2026-11-20 — Easy APK Distribution

- Added a GitHub Actions workflow that builds and tests the debug APK without an Android Studio installation.
- Added direct APK artifact download from Actions.
- Added optional tagged GitHub release publication from the workflow.
- Published the repository publicly so end users can download releases without repository access.
- Updated README installation instructions to use a downloaded APK rather than an Android Studio build.

### 2026-11-20 — Cloud Build Fix

- Replaced Kotlin callback method-reference calls in `VoiceInputManager` with explicit lambda forwarding to eliminate a recursive type-inference error found by the GitHub Actions compiler.

### 2026-11-20 — First Public APK Release

- GitHub Actions unit tests and the debug APK build now pass.
- Published release `v0.1.7` with a downloadable `app-debug.apk` asset.
- Repository is public so users can access releases directly without Android Studio or repository permissions.

### 2026-11-20 — Android Play Protect Guidance

- Added `INSTALL_ANDROID.md` with safe sideload testing, Play Protect explanation, ADB installation, and the Google Play production route.
- Added README guidance explaining that Play Protect may block powerful sideloaded apps even when GitHub Actions builds them successfully.

### 2026-11-20 — Quick Settings Activation

- Added a Quick Settings tile that launches the Control X overlay.
- Added an explicit `MainActivity` overlay-launch action used by the tile.
- The Android power button's three-second OEM gesture cannot be captured by a normal third-party app; it remains an OEM/system integration requirement.

### 2026-11-20 — Quick Settings Build Fix

- Restored `onNewIntent` and launch-intent handling to class scope in `MainActivity`; they must not be declared inside the Compose launch lambda.

### 2026-11-20 — Startup Crash Fix

- Made `SpeechRecognizer` lazy and available only when voice listening starts.
- Added speech-recognition availability checking and a clear error instead of constructing the recognizer during app startup.
- Added a shared uncaught-exception capture that is displayed on the next dashboard launch for diagnosis.

### 2026-11-20 — Human-Gesture Engine

- Expanded `GestureExecutor` with tap, double tap, long press, swipe, drag/drop, pinch, text injection, IME actions, selection, copy, cut, paste, and global navigation methods.
- Added bounded 10-step `ReActEngine.runGoal` execution with stale-node rejection, settle delay, loop guards, auto-scroll recovery, and safety verdict enforcement.
- Added lazy hardware initialization and defensive startup try/catch handling in `MainActivity`.
- Added global uncaught-exception capture that persists crash details in shared preferences for safe next-launch diagnostics.

### 2026-11-20 — Cross-Platform Architecture Foundation

- Added `docs/CROSS_PLATFORM_ARCHITECTURE.md` describing platform-neutral core, Android/iOS adapters, iOS limitations, shared safety, and implementation phases.
- Added platform-neutral Kotlin contracts under `com.controlx.core` for voice, fast path, perception, gesture, brain, and safety ports.

### 2026-11-20 — Assistant Trigger Bootstrap

- Replaced eager permission snapshots with a first-frame Compose UI and deferred background permission refresh.
- Added persistent `filesDir/last_crash.txt` crash capture and an on-screen red diagnostic card with copy/clear actions.
- Added Android `VoiceInteractionService`, session service, and `ACTION_ASSIST` fallback activity declarations.

### 2026-11-21 — Working Fast-Path Controls

- Wired app close/home/back/recents/notifications/quick-settings actions to the active accessibility service.
- Added accessibility scroll commands and coordinate gesture fallback.
- Added flashlight, exact media-volume, app launch, dial, alarm, timer, battery status, Wi-Fi/Bluetooth/display/hotspot/DND settings entry points.
- Added CAMERA runtime onboarding alongside microphone permissions.
- Agent/LLM execution remains separate and is not invoked by Fast-Path commands.

### 2026-11-21 — Universal Automation Foundation

- Added dynamic accessibility screen capture and ephemeral node resolution through `ScreenNodeParser`.
- Added `AgentNodeExecutor` for grounded click, text, scroll, coordinate, and system-action execution.
- Added `UniversalTaskRunner` for app launch, bounded ReAct execution, UI settle, loop detection, and explicit capability failures.
- Groq integration remains disabled until an API key is configured by the user.

### 2026-11-21 — Encrypted Groq Configuration

- Added encrypted local Groq API-key storage using AndroidX Security.
- Added settings load, save, and clear controls.
- Overlay unhandled commands now enter `UniversalTaskRunner` instead of stopping after Fast-Path mismatch.

### 2026-11-21 — MySQL Command History

- Added Dockerized MySQL 8.4 schema and least-privilege backend API structure.
- Added bearer-token protected `POST /api/v1/commands` storage endpoint.
- Added Android encrypted API configuration and command-history synchronization.
- Android uses a random device ID and never receives MySQL credentials.

### 2026-11-21 — Professional Dashboard

- Replaced the basic stacked dashboard with a scrollable dark control-center UI.
- Added dedicated permission, Groq AI, database, launch, and Fast-Path cards.
- Added visible Groq and backend configuration status.
- Added masked API-key/token fields and explicit save/clear feedback.

### 2026-11-20

- Created private GitHub repository `vivekoffical08-jpg/Control-X`.
- Set `main` as the default branch.
- Added native Kotlin/Compose project foundation.
- Added permission snapshot and onboarding entry points.
- Added typed action policy and fast-path command areas.
- Added Android `SpeechRecognizer` adapters.
- Added accessibility snapshot sanitizer and ephemeral node IDs.
- Added node, coordinate, text, scroll, and global gesture execution interfaces.
- Added Groq JSON-object client and 10-step ReAct safeguards.
- Added shared emergency kill switch and volume-down accessibility handling.
- Added sensitive-action and credential blocks.
- Added permission-gated overlay and assistant surface foundations.
