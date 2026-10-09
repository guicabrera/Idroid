---
tags:
  - idroid
  - architecture
  - adr
created: 2026-10-09
modified: 2026-10-09
decision_date: 2026-10-09
status: accepted
deciders: [Guilherme Cabrera]
supersedes:
superseded_by:
---

# ADR-0002: Automatic clipboard sync between Android and macOS

> **Summary:** Idroid syncs the clipboard automatically and bidirectionally between the Android phone and paired Macs, with no per-copy confirmation; on Android this requires an Accessibility Service to read clipboard changes made by other apps while Idroid is in the background.

## Context and problem

A core goal of the project ([Project Vision](../01-project-overview/project-vision.md)) is a Universal-Clipboard-equivalent experience: copy on one device, paste on the other, without an explicit "share" action. macOS has no platform restriction here — any app can poll `NSPasteboard.changeCount` at any time. Android is different: since Android 10, an app can only read the clipboard when it is the focused/foreground app, the default input method (IME), or has an Accessibility Service enabled. Without addressing this, "automatic" sync is only possible while the Idroid app itself is open, which defeats the goal.

A second, orthogonal question is policy: the clipboard routinely holds sensitive data (passwords, OTP codes, tokens). Deciding to sync "automatically, always" versus "automatically, with opt-out" versus "ask every time" changes both the UX and the privacy posture of the whole feature.

## Decision drivers

- Clipboard sync must work even when Idroid is not the foreground app on Android (that is the whole point of "automatic").
- Should not require the user to switch their system keyboard (too much friction for a background feature).
- Should minimize the amount of custom/fragile permission plumbing if possible, but not at the cost of defeating the feature.
- The user has explicitly prioritized frictionless automatic sync (matching Apple's Universal Clipboard) over per-copy confirmation, accepting the resulting privacy trade-off for their own personal-use case.

## Options considered

### Option 1: Accessibility Service (chosen)

- **Benefits:** Lets the Android app observe clipboard changes made by any app, in the background, without the user needing to reopen Idroid or switch keyboards. This is the only option that delivers true "copy anywhere, it just shows up" behavior.
- **Drawbacks:** Accessibility Service is a sensitive Android permission; Google Play requires a declared, reviewed use case for apps that request it, and the user must enable it manually once in system Settings → Accessibility (cannot be granted through a normal runtime permission dialog). Slightly higher battery/background resource use than a passive approach.

### Option 2: Foreground-only capture

- **Benefits:** No special permission; simplest to implement and to get approved on Google Play.
- **Drawbacks:** Only captures what was copied while Idroid itself was open and focused — does not satisfy the "automatic" goal, since most copies happen from other apps (browser, messaging, notes).

### Option 3: Custom IME (keyboard app)

- **Benefits:** Clipboard access is always available to the active IME without Accessibility Service.
- **Drawbacks:** Requires the user to switch their system default keyboard to Idroid's, which is high-friction and unrelated to the keyboard the user actually wants to type with; rejected as disproportionate to the problem.

### Option 4: Manual trigger (share action) instead of automatic sync

- **Benefits:** No special permission, zero ambiguity about what gets sent, matches the already-planned file/text sharing flow.
- **Drawbacks:** Not actually "automatic" — this is the status quo the user explicitly asked to go beyond (see the originating request: "quero também ter o copy paste nativo e automático").

## Decision

**Chosen option: Option 1 — Accessibility Service on Android, bidirectional automatic sync, no per-copy confirmation.**

The Android app declares an Accessibility Service whose only purpose is observing clipboard changes, with the required Google Play disclosure describing that exact purpose. On every clipboard change (either device), the new content is pushed to all currently paired and reachable devices using the same transport as file/text sharing ([ADR-0001](./ADR-0001-transport-and-relay-architecture.md)): direct tailnet connection first, Mac mini relay as fallback if the peer is offline. Content is synced with no confirmation dialog, matching the user's explicit preference for frictionless behavior over a safety prompt on every copy.

## Consequences

- **Positive:** Delivers the actual goal — copying anything, anywhere on the phone, makes it available on the Mac immediately, and vice versa, with no app-switching required.
- **Trade-off or risk:** Sensitive data (passwords, OTP/2FA codes, tokens) copied on either device is synced automatically to every paired device by default. The user has accepted this trade-off for personal use; if exposure grows (e.g., shared Mac mini account, more paired devices), revisit with a per-app exclusion list (already compatible with this architecture — it can be added later as a filter before the push step, without changing the sync model).
- **Trade-off or risk:** Google Play reviews Accessibility Service usage; the store listing must clearly disclose "reads clipboard content to sync it with your paired Mac" or risk rejection/suspension. This is a submission-process cost, not just an engineering one.
- **Trade-off or risk:** The user must manually enable the Accessibility Service once, in system Settings — this is a one-time onboarding step that cannot be skipped or automated.
- **Trade-off or risk:** Clipboard content synced through the Mac mini relay (when the direct path is unavailable) is temporarily at rest on that machine, same as file transfers; it inherits the retention/cleanup behavior already defined in ADR-0001 rather than needing a separate policy.
- **Trade-off or risk:** Continuous clipboard observation has a small but non-zero battery/background cost on Android; acceptable for a phone that is also expected to keep the Tailscale VPN connection alive for the rest of the project's functionality.

## Implementation notes

- Android: `AccessibilityService` configured to listen for clipboard-relevant events only (no need for full UI tree access); on change, read `ClipboardManager` content and hand it to the same sync client used for file/text transfers.
- macOS: poll `NSPasteboard.general.changeCount` (simple, no special permission) on a short interval, or move to a change-notification approach if polling proves too chatty.
- Sync payload needs a small metadata envelope (origin device, timestamp, content type: text/link vs. other) so a receiving device can avoid immediately re-sending back what it just received (echo loop prevention) — track the last-applied clipboard value's hash per device.
- Do not sync clipboard content that is flagged `isSensitive` by the OS where that signal is available (e.g., Android's `ClipDescription.EXTRA_IS_SENSITIVE`, used by password managers and one-time-code autofill) — this is a free, OS-provided signal and filtering it out does not conflict with the "no confirmation prompt" decision, since it never reaches the sync path in the first place.
- Keep the per-app exclusion list described above as a documented future enhancement, not part of the MVP.

## Links

- Related ADRs: [ADR-0001: Transport and relay architecture](./ADR-0001-transport-and-relay-architecture.md)
- Issue or ticket: none yet.
- References: Android documentation on clipboard access restrictions (Android 10+) and `ClipDescription.EXTRA_IS_SENSITIVE`; Apple Universal Clipboard (product reference for the target UX).

## Status history

| Date | Status | Notes |
|------|--------|-------|
| 2026-10-09 | accepted | Initial decision: Accessibility Service, automatic bidirectional sync, no confirmation prompt. |
