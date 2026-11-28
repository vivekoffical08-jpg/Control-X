# Control X

Maintained by **VIVEK KUMAR** (`@vivekoffical08-jpg`).

For complete architecture, module status, Android/OEM limitations, security boundaries, build instructions, and change history, see [PROJECT_DOCUMENTATION.md](PROJECT_DOCUMENTATION.md).

For the Android/iOS separation strategy and iOS platform constraints, see [docs/CROSS_PLATFORM_ARCHITECTURE.md](docs/CROSS_PLATFORM_ARCHITECTURE.md). For the original master specification reference, see [docs/CONTROL_X_MASTER_FEATURE_LIST.txt](docs/CONTROL_X_MASTER_FEATURE_LIST.txt).

## Install the Android app

No Android Studio or developer tools are required for users.

1. Open the **[latest GitHub release](https://github.com/vivekoffical08-jpg/Control-X/releases/latest)**.
2. Download the attached debug APK (`Control-X-v0.1.<build>-debug.apk`).
3. On Android, allow installation from the browser/file manager when prompted.
4. Open Control X and grant **Microphone** and **Overlay** (plus **Contacts** if you want call/SMS-by-name). **Accessibility stays OFF by default** so sideloaded builds do not trigger Google Pay / UPI security blocks.

If Android shows **“App blocked to protect your device”**, read [INSTALL_ANDROID.md](INSTALL_ANDROID.md). This can happen for sideloaded APKs that request Accessibility, Overlay, Camera, or Microphone access. It does not mean the APK was compiled incorrectly.

## Activate Control X

Control X supports multiple activation paths without requiring Accessibility:

1. **Quick Settings tile (`Control X`):** pull down the notification shade, edit Quick Settings, drag **Control X** into your active tiles, and tap it anytime to open the overlay over the current app.
2. **Ongoing Notification (`Ask Control X`):** while Control X is active, tap the notification or its **Ask Control X** action button from any screen to start listening immediately.
3. **Floating Quick-Trigger Bubble (`CX`):** tap **BUBBLE: ON** in the dashboard to keep a draggable `CX` pill on your screen edge (single-tap launches voice over any app; long-press hides it). It uses only `SYSTEM_ALERT_WINDOW` and hides automatically during Google Pay / banking Payment Safe Mode.
4. **Launcher Shortcut (`Ask Control X`):** long-press the Control X app icon on your home screen or pin the **Ask Control X** shortcut for 1-tap voice invocation.
5. **Default Digital Assistant (`ACTION_ASSIST` / OEM power-hold):**
   - Open **Control X** and grant **Microphone** and **Overlay** (Accessibility is optional).
   - Open **Settings > Apps > Default apps > Digital assistant** (or OEM equivalent: **Assistant app**, **Default assistant**, **Assist & voice input**).
   - Select **Control X**; once Microphone and Overlay are granted, power-hold or corner-swipe launches the overlay directly over whatever app is open.

A normal third-party app cannot directly intercept a 3–4 second power-button press. It works only when Android/OEM Settings routes the power-hold gesture to the selected digital assistant. Menu names, power-hold behavior, lock-screen behavior, and availability vary by phone; there is no guarantee on every Android OEM.

### In-App Voice Actions & Automation

- **Zero-Accessibility in-app actions:** launch any installed app, search inside apps (`"search lo-fi beats on Spotify"`, `"find Kotlin compose on YouTube"`), message on WhatsApp, draft emails, start Maps search or navigation (`"navigate to India Gate"`), launch camera photo/video mode, create calendar events, dial contacts, draft SMS, set alarms/timers, and toggle flashlight/volume.
- **Chained UI automation (`"open <app> and <task>"`):** when optional Screen Automation is turned ON, compound commands open the target app and run bounded backend-planned screen steps with per-action confirmation.

**Battery optimization:** use **Battery exemption > SET UP** for the direct Android battery-exemption prompt. If the OEM blocks that prompt, the app opens the OEM battery optimization list as a fallback.

**Optional Device Admin:** use **Device Admin > SET UP** only if you want `lock` commands to use Android Device Policy instead of Accessibility. Device Admin activation is user-controlled and may require confirmation or an alternate Settings flow by OEM.

**Payment safety:** Control X keeps Accessibility **OFF** by default so daily voice, app launching, calling, SMS drafts, alarms, search, media, and backend AI commands work without triggering Google Pay or UPI blocks. If you enable optional Screen Automation in Settings, Control X automatically turns Accessibility off (`disableSelf()`) when entering detected banking/payment apps (configurable via **AUTO-OFF** toggle) and includes a **Disable Accessibility** button in its notification. If Google Pay previously cached a block while an unverified accessibility service was active, turn Accessibility off, clear Google Pay/Play Services cache, and restart your phone once. For wide release without `"unverified app"` warnings, distribute via a signed Google Play release ([INSTALL_ANDROID.md](INSTALL_ANDROID.md)).

The APK and SHA-256 checksum are automatically built and uploaded as workflow artifacts by GitHub Actions after each `main` branch update. Tagged GitHub Releases can be published manually via `workflow_dispatch` with release publishing enabled.

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
- Local app catalog with fuzzy matching and package install/update/removal refreshes; app labels and package names remain on-device unless the separately consented inventory upload is enabled.
- Optional local contact index after the user grants Contacts permission; ambiguous matches are declined, and matching a contact opens the dialer for user confirmation.
- Explicit `type`/`write` fast-path text entry only into a focused, editable, non-sensitive field outside protected apps.
- Android media-search routing, explicit Spotify search deep links, and YouTube app/browser fallback for media commands.
- Browser internet search through commands such as "search for Android 15 features".
- Native voice recognition binding and foreground overlay hosting.
- Contact-name dialing, YouTube/Spotify media search, universal app launch, and hotspot Settings automation.
- Atomic crash reporting and on-screen diagnostics.
- On first launch, Control X scans installed app labels/packages locally and can upload the inventory to the configured backend only after explicit upload consent.
- The inventory is an exact device snapshot: it includes all visible installed packages and launcher apps, excludes only Control X itself, sorts by package name, and replaces stale backend rows instead of retaining old entries.
- `play <song or video>` searches YouTube and uses the grounded screen agent to select the first matching result when YouTube or the browser exposes accessible results.
- Streaming `SpeechRecognizer` callback contract with partial and final results.
- Explicit confirmation is required for every ReAct automation step before it executes.
- Accessibility node sanitizer with ephemeral `cx_*` IDs and password/sensitive-text redaction.
- Accessibility click, text, scroll, coordinate gesture, and global-navigation actuator.
- NVIDIA NIM (`nvidia/nemotron-3-ultra-550b-a55b` default) JSON reasoning and NLU intent routing hosted securely on the backend (`server/`) — no `NVIDIA_API_KEY` or backend secret is stored in the APK or shown to end users.
- Dynamic per-device registration (`POST /api/v1/devices/register`) with MySQL token hashing and rate limits, plus dynamic OS-intent execution (`DynamicActionExecutor`) so any device handles natural language without relying on Accessibility.
- Accessibility is strictly optional with `disableSelf()` automatic payment protection and a 1-tap notification disable button.
- ReAct click/type actions must target visible compatible nodes from the current snapshot; coordinate clicks must remain inside that node's bounds. The screen is rechecked after approval and again immediately before dispatch.
- ReAct never sends an unapproved automatic scroll/click as a recovery after a failed action or unchanged screen.
- Bottom assistant overlay surface, latency transcript state, and execution-state styling.
- Agent proposals are never executable without explicit user confirmation.
- No arbitrary accessibility automation, credential entry, arbitrary shell execution, root behavior, hidden APIs, or destructive actions are enabled by this foundation.
- Automatic global ad/dialog dismissal remains disabled so a heuristic cannot dismiss payment or security dialogs.
- Device/OEM matching latency is not guaranteed; app/contact matching is local, while NVIDIA NIM Nemotron reasoning depends on network and service response time.

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
