# Control X

Maintained by **VIVEK KUMAR** (`@vivekoffical08-jpg`).

For complete architecture, module status, Android/OEM limitations, security boundaries, build instructions, and change history, see [PROJECT_DOCUMENTATION.md](PROJECT_DOCUMENTATION.md).

For the Android/iOS separation strategy and iOS platform constraints, see [docs/CROSS_PLATFORM_ARCHITECTURE.md](docs/CROSS_PLATFORM_ARCHITECTURE.md).

## Install the Android app

No Android Studio or developer tools are required for users.

1. Open the **[latest GitHub release](https://github.com/vivekoffical08-jpg/Control-X/releases/latest)**.
2. Download `app-debug.apk`.
3. On Android, allow installation from the browser/file manager when prompted.
4. Open Control X and grant Microphone, Overlay, and Accessibility permissions.

If Android shows **“App blocked to protect your device”**, read [INSTALL_ANDROID.md](INSTALL_ANDROID.md). This can happen for sideloaded APKs that request Accessibility, Overlay, Camera, or Microphone access. It does not mean the APK was compiled incorrectly.

## Activate Control X

The app provides a **Quick Settings** tile labeled `Control X`. After installation:

1. Open the notification shade.
2. Edit Quick Settings.
3. Drag **Control X** into the active tiles.
4. Tap the tile to open the overlay and start listening.

Control X also registers Android voice-assistant entry points:

1. Open **Control X**.
2. Enable **Microphone**, **Overlay**, and **Accessibility**.
3. Open **Settings > Apps > Default apps > Digital assistant** or the OEM equivalent.
   Other menu names include **Assistant app**, **Default assistant**, **Assist & voice input**, or **Voice input**.
4. Select **Control X** and keep any **Use text from screen** or **Screen context** option at the device default unless you explicitly need it.
5. Android or the OEM can then route `ACTION_ASSIST` and supported voice-hold gestures to Control X.

A normal third-party app cannot directly intercept a 3–4 second power-button press. It works only when Android/OEM Settings routes the power-hold gesture to the selected digital assistant. Menu names, power-hold behavior, lock-screen behavior, and availability vary by phone; there is no guarantee on every Android OEM.

**Battery optimization:** use **Battery exemption > SET UP** for the direct Android battery-exemption prompt. If the OEM blocks that prompt, the app opens the OEM battery optimization list as a fallback.

**Optional Device Admin:** use **Device Admin > SET UP** only if you want `lock` commands to use Android Device Policy instead of Accessibility. Device Admin activation is user-controlled and may require confirmation or an alternate Settings flow by OEM.

The APK is automatically built and uploaded by GitHub Actions after each `main` branch update. Maintainer builds can also publish a tagged release with the APK.

## Developer build

Android Studio is only needed to develop or debug the project:

Open the repository in Android Studio or install the Android SDK and run:

```powershell
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Current foundation

- Jetpack Compose single-activity app module.
- Permission snapshot and user-initiated access entry points for microphone, notifications, overlay, and write-settings access.
- Typed action model with an explicit allowlist and input validation.
- Cancellable Android `SpeechRecognizer` adapter and deterministic fast-path command parser.
- Opt-in accessibility service that publishes sanitized visible-node snapshots and safe global navigation only.
- Agent proposal validator that rejects node identifiers absent from the current snapshot.
- Synchronous kill-switch state and hard blocking for credential, financial, purchase, and destructive command terms.
- Minimal overlay controller whose bubble click invokes the kill switch; it refuses to show without explicit overlay permission.
- Public Android fast-path routing for launchable apps, torch, volume, settings panels, alarms, and timers.
- Browser internet search through commands such as "search for Android 15 features".
- Native voice recognition binding and foreground overlay hosting.
- Contact-name dialing, YouTube/Spotify media search, universal app launch, and hotspot Settings automation.
- Atomic crash reporting and on-screen diagnostics.
- Streaming `SpeechRecognizer` callback contract with partial and final results.
- Explicit confirmation is required for every ReAct automation step before it executes.
- Accessibility node sanitizer with ephemeral `cx_*` IDs and password/sensitive-text redaction.
- Accessibility click, text, scroll, coordinate gesture, and global-navigation actuator.
- Groq JSON-object ReAct planning contract with 10-step loop limits and fingerprint loop detection.
- Bottom assistant overlay surface, latency transcript state, and execution-state styling.
- Agent proposals are never executable without explicit user confirmation.
- No arbitrary accessibility automation, credential entry, arbitrary shell execution, root behavior, hidden APIs, or destructive actions are enabled by this foundation.

## Planned execution paths

The fast path will route deterministic commands through public Android intents and APIs. The agent path will later consume a privacy-minimized accessibility snapshot and return one schema-validated atomic action at a time. Android and OEM restrictions may make some operations unavailable; unsupported operations must be reported rather than simulated.

## Build order

1. Project and permission foundation
2. Voice and text input
3. Fast-path router
4. Accessibility perception and execution
5. Bounded LLM ReAct loop
6. Overlay and dashboard hardening

The first device validation target is the Infinix Note 12 Pro 5G running Android 12/13 with XOS. Background process limits, overlay behavior, accessibility persistence, notification access, and battery management must be verified on-device.
