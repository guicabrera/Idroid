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

# ADR-0001: Transport and relay architecture for Android↔macOS sharing

> **Summary:** Idroid uses native Android and macOS apps that join a shared Tailscale tailnet; an always-on Mac mini on that tailnet acts as a store-and-forward relay when the direct peer is unreachable.

## Context and problem

There is no official AirDrop-equivalent between Android and macOS. To build one, we need to decide:

1. How the two apps find and connect to each other (same Wi-Fi, different networks, mobile data).
2. What happens when the destination device is offline or unreachable.
3. Whether to build native apps per platform or a single cross-platform codebase.

Constraints: no budget for always-on cloud infrastructure beyond what we already run; the user already owns a Mac mini that stays powered on at home; devices frequently leave the local network (phone on mobile data, laptop away from home).

## Decision drivers

- Must work when devices are not on the same LAN (not just local Wi-Fi sharing).
- Must not require opening inbound ports on a home router.
- Should avoid paying for or operating public cloud relay infrastructure.
- Should degrade gracefully: if the recipient is offline, the transfer should not simply fail.
- Each app should feel native (Share Sheet on Android, Share/Services on macOS), which argues against a shared cross-platform UI layer.

## Options considered

### Option 1: Local-network only (mDNS/Bonjour discovery + direct HTTP/WebSocket)

- **Benefits:** Simple, no third-party dependency, fast, works without internet.
- **Drawbacks:** Only works when both devices are on the same LAN; no delivery when either device is remote or offline.

### Option 2: Wi-Fi Direct / Bluetooth LE

- **Benefits:** No router/AP required for discovery.
- **Drawbacks:** macOS has no native Wi-Fi Direct support compatible with Android's implementation; at best BLE could assist discovery/pairing while data still needs Wi-Fi. Does not solve the "different networks" or "offline" cases.

### Option 3: Public cloud relay (self-hosted or third-party)

- **Benefits:** Works from anywhere; no dependency on a specific home device being online.
- **Drawbacks:** Requires running or paying for public-facing infrastructure, exposes a public endpoint to secure, ongoing hosting cost.

### Option 4: Tailscale tailnet + Mac mini as always-on relay (chosen)

- **Benefits:** All devices (phone, MacBook, Mac mini) join one private, encrypted (WireGuard) network via Tailscale; devices address each other by stable MagicDNS names regardless of network changes; no port forwarding; Tailscale attempts direct peer-to-peer connections automatically, falling back to Tailscale's own relays (DERP) only when needed; the Mac mini, already owned and always-on, can additionally run a small relay/inbox service for asynchronous delivery when the target device itself is offline.
- **Drawbacks:** Requires the Tailscale app/VPN profile active on the Android phone (can be set to "always on"); the Mac mini must stay powered on and reachable for store-and-forward delivery; adds a dependency on Tailscale as a platform.

## Decision

**Chosen option: Option 4 — Tailscale tailnet with the Mac mini as an always-on store-and-forward relay.**

This is the only option that satisfies all decision drivers without operating public infrastructure: Tailscale solves cross-network connectivity and NAT traversal, and the already-owned Mac mini solves offline delivery by acting as a private inbox. Direct device-to-device transfer is attempted first (Tailscale peer-to-peer); the Mac mini relay is the fallback path, not the only path.

Native apps per platform (Kotlin/Compose on Android, Swift/SwiftUI on macOS) are chosen over a cross-platform framework (e.g., Flutter, Kotlin Multiplatform) because feeling native — appearing correctly in Android's Share Sheet and macOS's Share/Services menu — matters more to this project than sharing a UI codebase, and the shared logic that does benefit from reuse (pairing, transfer protocol) lives in the relay API contract, not the UI layer.

## Consequences

- **Positive:** No public-facing infrastructure to secure or pay for; devices reachable by stable names via MagicDNS; transfers still succeed when the recipient is temporarily offline; encryption in transit is provided by the tailnet (WireGuard) and does not need to be reimplemented from scratch for the MVP.
- **Trade-off or risk:** The whole system depends on Tailscale's availability and on the Android app being able to keep its VPN connection alive in the background; mitigate by documenting "always on" VPN setup and, if needed later, adding a foreground service for reliability.
- **Trade-off or risk:** The Mac mini is a single point of failure for asynchronous delivery; acceptable for a personal-use MVP, revisit if reliability requirements grow.
- **Trade-off or risk:** TLS between devices is optional at the transport layer since the tailnet itself is encrypted, but pairing/authentication between the two apps still needs its own mechanism (see Implementation notes) so that any device on the tailnet cannot silently send files to another.

## Implementation notes

- Each device is identified on the tailnet via Tailscale MagicDNS (e.g., `macmini.tailnet-xyz.ts.net`); no hardcoded IPs.
- Transfer flow: try a direct connection to the peer's tailnet address first; if the peer is unreachable, `POST` the payload to the Mac mini relay's inbox instead.
- Relay service on the Mac mini: minimal HTTP(S) API (`POST /upload`, `GET /inbox`, `DELETE /item/{id}`) plus a WebSocket channel to push "new item" notifications to a connected client; runs as a `launchd` daemon so it survives reboots and user logout.
- Retention: items in the Mac mini inbox are deleted automatically after a fixed window (e.g., 7 days) to bound disk usage.
- Pairing/authentication: devices pair once via QR code or PIN, exchanging a per-device token (and optionally a TLS certificate) used to authenticate subsequent requests to each other and to the relay. This is what prevents any tailnet member from impersonating a paired device, independent of Tailscale's own network-level trust.
- Optional later: expose the relay over HTTPS using `tailscale serve`, instead of managing certificates manually.

## Links

- Related ADRs: none yet.
- Issue or ticket: none yet.
- References: [Tailscale documentation](https://tailscale.com/kb/), prior art — KDE Connect, LocalSend.

## Status history

| Date | Status | Notes |
|------|--------|-------|
| 2026-10-09 | accepted | Initial decision: Tailscale tailnet + Mac mini relay, native Android/macOS apps. |
