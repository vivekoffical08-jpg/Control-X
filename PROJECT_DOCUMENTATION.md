# Control X Project Documentation

This file is the maintained source of truth for Project Control X. It documents the implementation, architecture, Android constraints, security behavior, build state, and future work.

**Rule for future changes:** Every feature addition, behavior change, dependency change, permission change, Android/OEM limitation, or security decision must update this document and the relevant module section.

## 1. Project Overview

Control X is a native Kotlin Android voice and screen-control application. It is designed with three execution layers:

1. **Local Fast-Path:** deterministic parsing and public Android APIs (<30ms offline execution).
2. **Backend Dynamic Intent Router:** proxies natural-language commands to NVIDIA NIM (`nvidia/nemotron-3-ultra-550b-a55b` default) on the server (`server/`), using zero client-side NVIDIA API keys and mapping to dynamic Android Intents/APIs without requiring Accessibility.
3. **Optional Agent Path:** accessibility-screen grounding and a bounded ReAct loop proxied through the backend for complex cross-app UI tasks.

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
      BackendAiClient.kt
      ReActEngine.kt
      SnapshotActionValidator.kt
      UniversalTaskRunner.kt
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
      OverlayControllerService.kt
    platform/
      AppCatalogManager.kt
      CommandApiClient.kt
      ContactCatalogManager.kt
      PermissionRepository.kt
      PermissionSnapshot.kt
      PackageChangeReceiver.kt
    router/
      AppLauncher.kt
      ContactCaller.kt
      DynamicActionExecutor.kt
      FastPathRouter.kt
      HotspotAutomation.kt
      MediaPlaybackController.kt
server/
  src/main/kotlin/com/controlx/server/
    DeviceAuthRepository.kt
    NvidiaNimProxy.kt
    Server.kt
    security/
      ExecutionGuard.kt
      PaymentSafeMode.kt
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
- Resolve `open` and `launch` requests against the local app catalog, using exact package names or conservative, ambiguity-aware label matching.
- Pre-index installed application labels and package names locally at app boot; refresh the catalog when a package is added, removed, or replaced, then resolve launchable apps with conservative fuzzy matching.
- Optionally pre-index contacts on-device after Contacts permission is granted; ambiguous contact matches are declined and calls open `ACTION_DIAL` so the user confirms before placing a call.
- Route explicit `type`/`write` commands into the focused editable field only when that node and foreground app pass sensitive-action checks.
- Route media search through Android's `INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH`, explicit Spotify search deep links when requested, and YouTube app/browser fallback.
- Route `search`, `google`, `look up`, and `find` commands to a Google browser search URL.
- Return explicit errors for Back and Recents when no accessibility service is available.

### Android limitations

- Some settings panels may vary by Android version and OEM.
- Third-party apps cannot silently toggle every system setting.
- Android package visibility and OEM package-manager behavior limit which installed apps can be indexed; callers still need the Contacts runtime permission for contact-name resolution.
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

## 8. Backend AI Proxy & ReAct Agent Brain

All AI inference is proxied through the backend server (`server/`), keeping `NVIDIA_API_KEY` hidden in server environment variables rather than inside the APK or on Android devices.

- Android automatically registers a random `deviceId` with `POST /api/v1/devices/register` and receives a per-device bearer token stored in `EncryptedSharedPreferences` (`CommandApiClient`).
- Unmatched natural-language commands call `POST /api/v1/assistant/interpret`, allowing `NvidiaNimProxy` on the backend to select a typed device intent (`OPEN_APP`, `DIAL_CONTACT`, `DRAFT_SMS`, `PLAY_MEDIA`, `WEB_SEARCH`, `SET_ALARM`, `SET_TIMER`, `TOGGLE_TORCH`, `SET_VOLUME`, `OPEN_SETTINGS`, `LOCK_SCREEN`, `ANSWER_TEXT`, or `NEEDS_SCREEN_AGENT`) executed locally by `DynamicActionExecutor` without Accessibility.
- Multi-step UI automation (`ReActEngine` via `AgentBrainPort`) calls `POST /api/v1/agent/plan`, where `NvidiaNimProxy` sends a strict JSON request (`chat_template_kwargs.enable_thinking = false`) to:

`https://integrate.api.nvidia.com/v1/chat/completions` (default model: `nvidia/nemotron-3-ultra-550b-a55b`)

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
- Click and type actions must target a visible compatible node; coordinate clicks are accepted only inside the target node's bounds. Screen package and node fingerprints are checked again after approval and immediately before action dispatch.
- History and screen fingerprints detect repeated actions.
- Unknown action names are rejected.
- Sensitive nodes and credential/destructive instructions are blocked by policy.
- API keys (`NVIDIA_API_KEY`) must be supplied strictly in the backend server environment; none are stored in Android source or client storage.
- Per-device bearer tokens and MySQL quota limits (`devices`, `ai_usage_logs`) protect the backend proxy against runaway device usage.
- Screen data must not be sent over the network unless optional Accessibility is enabled and the user invokes an assistant command that falls through to the agent path.

## 9. Safety and Kill Switch

The shared `ExecutionGuard` provides a stopped/allowed state.

- Accessibility key-event handling stops execution when Volume Down is pressed.
- Overlay bubble and assistant panel use the same shared guard.
- `SafetyGuardian` blocks sensitive packages and credential terms.
- `PaymentSafeModeMonitor` watches accessibility window-state events and identifies payment/banking
  apps from known package prefixes and package-name keywords.
- Entering a detected payment/banking app immediately stops the shared execution guard, clears the
  screen snapshot, cancels the active command, and removes both overlay windows. When **AUTO-OFF**
  is enabled (default ON), `ControlAccessibilityService` also calls `disableSelf()` on Android API 24+
  so the service itself turns off automatically.
- The ongoing notification exposes a 1-tap **Disable Accessibility** action as well as a direct link
  to Android Accessibility Settings. Android does not let third-party apps silently re-enable Accessibility,
  and if Google Pay / Play Services already cached a security block earlier while Accessibility was ON,
  clearing Google Pay/Play Services cache and rebooting the device once may still be required.
- Accessibility is strictly **optional**: Voice, Overlay, App Launching, Contact Dialing, SMS Drafts,
  Media Search, Alarms, Flashlight, Volume, Device Admin Lock, and Backend Dynamic NLU intents all run
  with Accessibility turned OFF so daily usage remains compatible with Google Pay / UPI apps.
- Leaving the detected app does not restore the overlay or resume the cancelled task. The user must
  invoke Control X again. Agent execution checks the safe-mode state before planning and immediately
  before dispatching actions.
- Financial, purchase, transfer, and deletion language is classified as biometric approval.
- `SensitiveActionPolicy` provides command-level hard blocks.
- Password/PIN/OTP/CVV entry is not implemented.
- Data deletion and financial actions are not implemented.
- No arbitrary shell, root, hidden API, device-admin, or silent installation behavior is implemented.

### Sensitive package policy

Authenticator, credential-related, financial, and protected system package families are blocked.
Payment Safe Mode detection currently relies on known package prefixes and package-name keywords
(bank, UPI, pay, payment, wallet, checkout); OEM-specific apps with unrelated package names may not
be detected automatically. Checkout pages inside a general-purpose browser may also be missed,
because the monitor uses app package names rather than inspecting website content. Users can use the
foreground-service notification's Accessibility Settings action and disable Control X manually if
a payment provider still blocks checkout merely because the service is enabled. Re-enabling
Accessibility remains an explicit user action.

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
- Detected payment/banking foreground apps cause immediate removal of the panel and scrim. Changing
  overlay type or flags cannot guarantee compatibility with apps that use obscured-touch checks.
- A full neon execution aura and production soundwave visualizer are not yet implemented.

## 12. Assistant Activation and Invocation

The app implements `VoiceInteractionService`, `VoiceInteractionSessionService`, and
`ACTION_ASSIST` for supported Android/OEM activation paths. The dashboard exposes
the device voice-input/default-assistant settings route and explains that menu names
vary.

The power button cannot be intercepted by a normal third-party app. The supported
power-hold flow is indirect: the user selects Control X in Android/OEM digital
assistant Settings, and the OEM routes its power-hold gesture to the default assistant.
Availability depends on Android and OEM policy, lock-screen state, Accessibility,
overlay permission, and process retention.

## 13. Current Hardening

- Fast-Path and agent paths reject credential, financial, purchase, and destructive commands.
- Password/PIN/OTP/CVV/passcode nodes are redacted and cannot receive agent text.
- Agent execution is snapshot-grounded: node IDs must exist in the latest accessibility snapshot.
- ReAct execution is limited to 10 steps and stops on the shared kill switch.
- Repeated action/screen states are blocked as anti-loop behavior.
- Every non-FINISH/FAIL ReAct action requires an explicit Allow decision in the overlay.
- Failed or unchanged ReAct steps terminate safely rather than sending an unapproved fallback gesture.
- NVIDIA NIM receives the foreground package and reported accessibility window class as activity context. The app supports only its schema-validated atomic action set; unsupported gesture/action names are rejected rather than simulated.
- No sub-180ms latency is guaranteed: local catalog lookup is the fast path, while speech recognition, Android scheduling, network transport, NVIDIA NIM response time, and OEM behavior are variable.
- Android voice recognition uses `RecognitionService` with partial/final SpeechRecognizer callbacks.
- The persistent overlay runs through `OverlayControllerService` with Android foreground-service notification handling.
- Universal app discovery, contact dialing, YouTube/Spotify media routing, hotspot Settings automation, and safe ad/dialog dismissal are implemented to the extent exposed by Android and OEM APIs.
- Overlay and typed/voice commands share the kill switch and stop immediately on Volume Down.
- Diagnostics show startup crashes and permission state for onboarding and troubleshooting.

## 14. Known Platform Limitations

- Some OEMs do not offer a 3–4 second power-hold assistant setting.
- Some settings routes change labels or require a separate system app selection step.
- Background speech recognition may be suspended by OEM battery/process policies.
- Secure apps, banking apps, authentication apps, device policy, native canvases,
  and custom widgets may expose no safe automation surface.
- Third-party apps cannot silently enable Accessibility, Overlay, Default Assistant,
  or protected system settings.
- Battery optimization can be requested through
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; OEM policy may still require the
  general Ignore battery optimization Settings screen.
- Device Admin is optional and limited by policy to `force-lock`. It is never
  enabled silently, and lock-only fallback through Accessibility remains available.
- Automatic ad/dialog dismissal is disabled. Financial, banking, payment, checkout,
  wallet, and credential packages are blocked from ReAct automation; payment gateways
  are never clicked or completed autonomously.
- Payment Safe Mode pauses active voice/agent work and removes both overlay windows when a matching
  payment/banking app becomes foreground. It does not silently disable the Accessibility Service:
  payment SDKs may still detect the enabled service, so users may need to open Accessibility
  Settings from the persistent notification and disable Control X themselves. OEM app package
  naming and foreground event behavior vary; checkout pages inside general-purpose browsers may not
  be identified, so automatic detection is best-effort, not a guarantee.
- Installed app discovery is performed locally at dashboard startup. Package names and
  labels are sent to `/api/v1/app-inventory` only when upload consent is enabled and
  the backend/API token are configured. Discovery merges `getInstalledPackages(0)`
  with launcher-package results, excludes Control X, and the backend replaces the
  device's stale inventory rows with one exact snapshot.
- Media playback requests such as `play Humsafar` open the YouTube app with a search
  query. When accessibility and NVIDIA NIM Nemotron are available, the grounded ReAct loop waits for
  results and selects the first clearly matching accessible video. Android/OEM browser
  and app behavior can limit automatic result selection.

## 12. Android and OEM Constraints

- Android 11-14 package visibility and system APIs differ.
- Scoped storage limits arbitrary file access.
- Background speech recognition and accessibility persistence depend on OEM policy.
- XOS may aggressively stop background processes or require battery-exemption configuration.
- Overlay and accessibility enablement may require manual steps.
- Samsung, Xiaomi, OnePlus, Oppo, Vivo, Transsion, and Pixel variants differ.
- Work profiles and device owner policies may prohibit actions.
- Secure views and banking apps may deliberately hide accessibility content.
- Automatic global dialog/ad dismissal remains disabled. Although simple labels such as “Skip” or “Close” can look harmless, automatically clicking them across apps could dismiss security, consent, or payment prompts.
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
- NVIDIA NIM network behavior

Build verification must be performed in Android Studio or a host with JDK 17 and Android SDK 35.

## 14. Planned Updates

Future development should proceed in this order:

1. Instrumented permission onboarding.
2. Device smoke-test harness.
3. Live voice UI and wake-word integration.
4. Executable fast-path controller implementations.
5. Accessibility service snapshot integration.
6. Gesture executor integration and settle verification.
7. NVIDIA NIM API configuration and secure key storage.
8. ReAct loop state machine.
9. Overlay UI, soundwave visualizer, and execution aura.
10. Infinix XOS persistence testing.
11. Biometric approval UI for future explicitly allowed high-risk operations.

## 15. Change Log

### 2026-11-27 — Backend AI Key Hiding, Dynamic Intents & Payment-Safe Accessibility

- Moved all NVIDIA NIM (`nvidia/nemotron-3-ultra-550b-a55b`) API keys and inference calls onto `server/` (`NvidiaNimProxy.kt`, `DeviceAuthRepository.kt`, `Server.kt`) with MySQL `devices` and `ai_usage_logs` per-device token registration (`POST /api/v1/devices/register`) and daily/per-minute quotas.
- Removed client-side `NvidiaKeyStore` and `NvidiaNimApiClient` from Android so zero `nvapi-...` keys exist in the APK or UI; added `BuildConfig.DEFAULT_BACKEND_URL` plus automatic per-device registration and an optional Server URL card in `MainActivity`.
- Added dynamic natural-language intent interpretation (`POST /api/v1/assistant/interpret` + `DynamicActionExecutor`) supporting `DRAFT_SMS`, `OPEN_APP`, `DIAL_CONTACT`, `PLAY_MEDIA`, `SET_ALARM`, `SET_TIMER`, `WEB_SEARCH`, `TOGGLE_TORCH`, `SET_VOLUME`, `OPEN_SETTINGS`, `LOCK_SCREEN`, and `ANSWER_TEXT` on any phone without Accessibility.
- Made Accessibility strictly optional for Google Pay / UPI safety, added automatic `disableSelf()` in `ControlAccessibilityService` when entering detected payment apps (`AUTO-OFF: ON` default), added 1-tap **Disable Accessibility** in `OverlayControllerService` notification and `MainActivity`, and added backend + Android unit tests (`ServerProxyTest.kt`, `AgentContractTest.kt`).

### 2026-11-27 — Replace Groq AI with NVIDIA NIM (Nemotron)

- Replaced `GroqApiClient` and `GroqKeyStore` with `NvidiaNimApiClient` and `NvidiaKeyStore`, routing ReAct agent planning to `https://integrate.api.nvidia.com/v1/chat/completions`.
- Set default model to `nvidia/nemotron-3-ultra-550b-a55b` (configurable in the **NVIDIA Nemotron AI** dashboard card alongside encrypted `nvapi-...` key storage).
- Added `chat_template_kwargs.enable_thinking = false`, `response_format` fallback handling, and `<think>...</think>` / code-fence JSON extraction in `NvidiaNimApiClient` using Gson (covered by new unit tests in `AgentContractTest.kt`).

### 2026-11-27 — PR #2 Integration & Master Spec Tracking

- Merged `vivekoffical08-jpg-control-x-master-feature` (PR #2) into `main`, bringing Payment Safe Mode (`PaymentSafeMode.kt`), disabled autonomous dialog dismissal (`InterruptionReflex`), local app/contact catalogs (`AppCatalogManager`, `ContactCatalogManager`, `LocalCatalogMatcher`, `PackageChangeReceiver`), backend app-inventory sync (`/api/v1/app-inventory`), fast-path `type`/`write`, and strict `SnapshotActionValidator` post-approval screen verification.
- Tracked `docs/CONTROL_X_MASTER_FEATURE_LIST.txt` in the repository and added `releases/*.apk` to `.gitignore` so prebuilt binaries are distributed via GitHub Releases rather than tracked in Git history.
- Updated `README.md` to reference `docs/CONTROL_X_MASTER_FEATURE_LIST.txt` and CI artifact/release naming (`Control-X-v0.1.<build>-debug.apk`).

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
