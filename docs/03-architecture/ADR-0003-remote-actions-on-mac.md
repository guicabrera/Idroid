---
tags:
  - idroid
  - architecture
  - adr
created: 2026-11-21
modified: 2026-11-21
decision_date:
status: superseded
deciders: [Guilherme Cabrera]
supersedes:
superseded_by: ADR-0005 (in part)
---

# ADR-0003: Remote actions on the Mac triggered from Android

> **Superseded in part by [ADR-0005](./ADR-0005-remote-terminal-and-ai-agent.md):** arbitrary commands are now allowed (free terminal on by default, plus SSH in the app). The named-actions part below still applies.
>
> **Summary:** Idroid lets the Android phone trigger a Mac-side allowlist of named actions (built-ins plus user-approved macOS Shortcuts), delivered live over the relay WebSocket with a short expiry; arbitrary shell execution is out of scope and left to Tailscale SSH.

## Context and problem

Beyond sharing files and the clipboard, it is useful to drive the Mac from the phone: lock or sleep it, open a URL, control media, or run a user-defined automation. All devices already share a tailnet ([ADR-0001](./ADR-0001-transport-and-relay-architecture.md)) and authenticate to the relay with a per-device token obtained at pairing.

Two things make this different from existing features:

1. **Blast radius.** Today a stolen device token lets an attacker drop items into an inbox. If the same token can run commands on the Mac, a stolen token becomes remote code execution on the user's computer. The macOS app is not sandboxed (ad-hoc signed by `macos/scripts/bundle.sh`, no entitlements), so it can spawn any process the user can.
2. **Timing semantics.** The relay is store-and-forward: `POST /upload` keeps an item for up to `IDROID_RETENTION_DAYS` (default 7) until the recipient fetches it. That is right for a file and wrong for a command: a "sleep the Mac" tapped on Monday must not fire when the Mac wakes up on Wednesday.

## Decision drivers

- A leaked Android device token must not grant arbitrary code execution on the Mac.
- A command must run promptly or not at all; stale commands must never execute.
- The set of actions should be extensible by the user without shipping a new app build.
- Reuse pairing, auth and the existing `/ws` channel rather than adding a new listener on the Mac.
- Full interactive shell access is already available through Tailscale SSH; Idroid should not duplicate it badly.

## Options considered

### Option 1: Arbitrary shell commands

The phone sends a command string; the Mac app runs it via `/bin/zsh -c`.

- **Benefits:** Maximum flexibility; trivial to implement.
- **Drawbacks:** A device token becomes a remote shell credential. Any bug in pairing, token storage on Android, or relay auth escalates to full compromise. Output streaming, interactivity, and quoting turn it into a poor terminal emulator. Duplicates Tailscale SSH.

### Option 2: Mac-side allowlist of named actions, extended via macOS Shortcuts (chosen)

The Mac app owns a list of actions. Built-ins cover common cases (lock screen, sleep display, open URL, media play/pause, volume). Users extend it by approving specific Shortcuts from the Shortcuts app; the Mac runs them with `shortcuts run "<name>"`, passing optional text input. The phone can only ask for an action ID that the Mac already published.

- **Benefits:** A leaked token can only trigger actions the user approved on the Mac, which is a bounded, visible set. Shortcuts gives extensibility (including "Run Shell Script" steps, if the user deliberately chooses that) without Idroid parsing commands. The action list is the UI on Android.
- **Drawbacks:** Less flexible than a shell. Requires the user to curate actions on the Mac. Depends on the Shortcuts CLI behaving well when run from a background app.

### Option 3: No Idroid feature; document Tailscale SSH + Termux/Termius

- **Benefits:** Zero code; full power; security delegated to Tailscale ACLs and SSH.
- **Drawbacks:** Not one-tap; no native Android integration (quick settings tile, widget, share target "open on Mac"). Typing commands on a phone is poor UX for the frequent small actions.

## Decision

**Chosen option: Option 2 — Mac-side allowlist of named actions, extended via Shortcuts**, with Option 3 documented as the answer for "I need a real shell".

Option 2 is the only one that keeps the token's blast radius bounded while still offering one-tap actions and user extensibility. Interactive shell access is a solved problem on the tailnet, so Idroid does not try to solve it again.

## Consequences

- **Positive:** One-tap Mac control from Android (app screen, quick settings tile, widget, and an "Open on Mac" share target for URLs).
- **Positive:** Compromise of the Android token yields only the approved actions, which the user can see and revoke on the Mac.
- **Trade-off or risk:** Commands cannot be queued for an offline Mac. The phone must show "Mac offline" instead of silently queuing. This is intentional.
- **Trade-off or risk:** A user who approves a Shortcut containing "Run Shell Script" re-opens the arbitrary-execution risk for that shortcut. Mitigate by showing a warning on approval when the shortcut is known to contain script steps, and by never offering an "allow all shortcuts" switch.
- **Trade-off or risk:** Introduces a second, non-inbox message type on the relay; the relay grows from pure store-and-forward to also routing live messages.

## Implementation notes

- **Relay:** add a live-message path that never touches the item store, e.g. `POST /live` (authenticated) which forwards a JSON envelope to the recipient's open WebSocket connections and returns `409`/`503` if the recipient is not online (`hub.IsOnline`). Nothing is persisted. Extend `NotifyEvent` (or add a sibling type) to carry the envelope.
- **Envelope:** `{ "type": "action.invoke", "id": "<uuid>", "action_id": "...", "input": "...", "sender_id": "...", "expires_at": "<now + 30s>" }`. The Mac rejects expired envelopes and remembers recent `id`s to drop replays.
- **Result:** the Mac answers with `action.result` (`ok` / `error` + short message) over the same live path so the phone can show success or failure. No stdout streaming.
- **Action catalogue:** the Mac publishes its list (`id`, display name, whether it takes input) via a live `actions.list` request or by pushing it on change; Android caches it for the UI but the Mac re-validates every invocation against its current allowlist.
- **Per-device grants:** the allowlist is per paired device, so a future second phone does not inherit everything.
- **macOS execution:** built-ins via AppKit/IOKit APIs where possible; Shortcuts via `Process` running `/usr/bin/shortcuts run`. Run with a timeout; never interpolate `input` into a shell string; pass it via `--input-path` or stdin.
- **Auditing:** keep a small local log on the Mac (time, device, action, result) visible in the app.
- **Android UI:** an actions screen, a quick settings tile for one pinned action, and a share target for URLs/text that invokes "open on Mac".
- **Out of scope:** Mac → Android actions (Android restricts background execution heavily; revisit separately), interactive shells, file system operations (see [ADR-0004](./ADR-0004-live-file-access.md)).

## Links

- Related ADRs: [ADR-0001](./ADR-0001-transport-and-relay-architecture.md), [ADR-0004](./ADR-0004-live-file-access.md)
- Issue or ticket: none yet.
- References: [Tailscale SSH](https://tailscale.com/kb/1193/tailscale-ssh), `man shortcuts`, prior art — KDE Connect "Run commands" plugin.

## Status history

| Date | Status | Notes |
|------|--------|-------|
| 2026-11-21 | proposed | Initial draft. |
| 2026-11-24 | superseded | Free terminal and in-app SSH adopted in ADR-0005. |
