# Control X Cross-Platform Architecture

## Scope

Control X should be built as a platform-agnostic intelligence engine with thin OS-specific execution adapters.

The Android implementation already provides:

- Voice input
- Fast-path routing
- Accessibility screen perception
- Accessibility gesture execution
- Overlay UI
- Safety and kill-switch logic
- NVIDIA NIM Nemotron ReAct planning

iOS support must respect Apple's sandbox, accessibility, automation, and App Store restrictions. A normal iOS app cannot use AccessibilityService-style screen control or dispatch arbitrary gestures across other apps.

## Recommended topology

```text
Platform-neutral core
  - command parsing
  - voice state machine
  - screen snapshot contracts
  - agent action schema
  - ReAct loop
  - safety policy
  - history and loop detection

Android adapter
  - SpeechRecognizer
  - PackageManager
  - CameraManager
  - AudioManager
  - AccessibilityService
  - GestureDescription
  - WindowManager overlay

iOS adapter
  - Speech/SpeechAnalyzer integration
  - own-app Siri/Shortcuts/App Intents
  - own-app navigation and UI automation
  - approved system intents
  - optional accessibility metadata only when exposed by Apple APIs
```

## Core contracts

The core must depend only on serializable models and abstractions:

- `VoiceInputPort`
- `FastPathPort`
- `ScreenPerceptionPort`
- `GestureExecutorPort`
- `TextInputPort`
- `GlobalNavigationPort`
- `SystemActionPort`
- `AgentBrainPort`
- `SafetyGuardianPort`
- `OverlayPort`

Each OS adapter implements these ports. The core must never import Android or iOS framework types.

## Android boundary

Android remains the implementation source for this repository.

Android adapters may use:

- `SpeechRecognizer`
- `PackageManager`
- `AccessibilityService`
- `GestureDescription`
- `WindowManager`
- `CameraManager`
- `AudioManager`

Hardware, permissions, accessibility enablement, and OEM restrictions must remain inside adapters. Core code must receive capability results and explicit failures.

## iOS boundary

A standard iOS app can integrate:

- `Speech` framework for its own supported voice capture
- Shortcuts/App Intents for user-approved actions
- its own app's SwiftUI/UIKit state
- user-granted notification, microphone, calendar, or location APIs
- approved URL schemes and universal links

A normal iOS app cannot:

- inspect arbitrary third-party app accessibility trees
- dispatch synthetic touch events across the OS
- install or drive another app silently
- override protected system settings
- add a persistent global overlay over every app
- intercept the hardware side-button gesture

For iOS screen automation, use Apple-supported workflows, Guided Access, Shortcuts, App Intents, or device-management/signed enterprise workflows rather than fabricating Android-style accessibility behavior.

## Shared agent loop

Both platforms should share:

1. User intent parsing
2. Structured agent-action schema
3. JSON grounding rules
4. Sensitive-action policy
5. Maximum 10-step loop
6. Screen fingerprint/loop detection
7. Explicit settle and verification
8. Structured success/error/capability-unavailable results

The action executor remains platform-specific.

## Distribution

Recommended implementation stack:

- Android: native Kotlin repository
- iOS: native Swift/SwiftUI repository or module
- Shared protocol: versioned JSON contracts
- Cross-platform product orchestration: optional backend or shared package

Do not share Android `AccessibilityNodeInfo`, `Rect`, `GestureDescription`, or `WindowManager` types with the iOS implementation.

## Security policy

The shared core must hard-block:

- passwords and credential entry
- PIN, OTP, and CVV entry
- money transfer and purchase automation
- destructive deletion and erase actions
- arbitrary shell or privileged access
- bypassing platform permissions
- unattended cross-app automation in sensitive applications

High-risk operations require fresh platform approval or user confirmation.

## Implementation phases

1. Extract platform-neutral command and agent models.
2. Add capability and result contracts.
3. Implement Android adapters around existing classes.
4. Define a versioned JSON/transport protocol for iOS.
5. Create a native SwiftUI iOS shell.
6. Add only iOS APIs permitted for normal app distribution.
7. Add shared loop and safety tests.
8. Add platform-specific instrumentation tests.
