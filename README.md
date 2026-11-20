# Control X

Control X is being built as a native Kotlin Android application with a deliberately constrained execution model.

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
- Streaming `SpeechRecognizer` callback contract with partial and final results.
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
