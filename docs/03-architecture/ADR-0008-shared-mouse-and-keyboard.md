---
tags:
  - idroid
  - architecture
  - adr
created: 2026-11-25
modified: 2026-11-25
decision_date: 2026-11-25
status: accepted
deciders: [Guilherme Cabrera]
supersedes:
superseded_by:
---

# ADR-0008: Shared mouse and keyboard (Mac → phone)

> **Summary:** Pushing the Mac's pointer past a chosen screen edge hands its mouse and keyboard to the phone, Synergy-style. The Mac captures input with an event tap and streams it over a live relay pipe; on Android a dedicated accessibility service draws a pointer, turns clicks/drags/scroll into gestures and types into the focused field.

## Context and problem

The user wants the Mac's keyboard and mouse to "flow" onto the phone across a screen edge. scrcpy (ADB + UHID) does it but depends on wireless debugging, which drops on reboot/network change.

## Decision

- **Transport:** `GET /input/{device_id}` on the relay is a websocket. The Mac opens it to the phone; the relay pushes `input_request` (sender_id) over the phone's normal `/ws`; the phone opens `/input/{mac_id}`; the relay bridges frames both ways and stores nothing. Unanswered requests time out after 30 s and the Mac reconnects.
- **Protocol (JSON text frames):** Mac → phone `enter{side,y}`, `leave`, `move{dx,dy}`, `down`, `up`, `rclick`, `scroll{dx,dy}`, `text{s}`, `key{k}`; phone → Mac `hello` (pipe is live), `exit{y}` (pointer left through the return edge).
- **Mac:** `InputShareController` — CGEvent session tap (needs Accessibility). At the chosen edge (right or left, only where no other display continues) and after a ~40 pt push, the cursor is frozen (`CGAssociateMouseAndMouseCursorPosition(false)`), hidden, and events are swallowed and forwarded. ⌘⎋ always returns. ⌘V sends the Mac clipboard as text; ⌘H = Home; ⌘Tab = Recents; right click = Back.
- **Android:** `InputControlService`, separate from the clipboard service so it's its own opt-in: `TYPE_ACCESSIBILITY_OVERLAY` pointer (not touchable), `dispatchGesture` for tap / long press / swipe-on-release / scroll, `ACTION_SET_TEXT` + `ACTION_SET_SELECTION` on the input-focused node for typing, global actions for Back/Home/Recents.

## Consequences

- No hover, and drags are replayed on release (a straight swipe), not live. Live drags with `continueStroke` are future work.
- Typing via `ACTION_SET_TEXT` doesn't fire per-key IME events: works in normal fields, may fail in games, terminals or apps that react to keystrokes. A custom IME is the fallback if needed.
- The accessibility service can read window content (to find the focused field) — broader than ADR-0002's clipboard service; it only acts on messages from the paired Mac.
- The Mac app is signed ad hoc: each rebuild may require re-granting Accessibility.
- Latency: Mac → relay (local on the Mac mini) → phone over Tailscale.

## Status history

| Date | Status | Notes |
|------|--------|-------|
| 2026-11-25 | accepted | Relay pipe, macOS controller and Android service implemented. |
| 2026-11-25 | accepted | Pointer felt sluggish through the relay (TCP, podman VM hop): moves now go straight Mac → phone over UDP (`hello` carries port, key and LAN/Tailscale IPv4s; `key\|seq\|dx\|dy` datagrams, stale ones dropped); clicks and keys stay on the relay pipe. Mac applies an acceleration curve and batches moves every 8 ms; phone holds a low-latency Wi-Fi lock while the pipe is open. |
