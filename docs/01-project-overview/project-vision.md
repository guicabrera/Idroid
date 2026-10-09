---
tags:
  - idroid
  - documentation
  - project-overview
created: 2026-10-09
modified: 2026-10-09
status: active
---

# Idroid — Project Vision

> **Summary:** Idroid brings AirDrop-like sharing (files, text, links, clipboard) between an Android phone and one or more Macs, without relying on any official cross-platform protocol.

## Problem

Apple's AirDrop only works between Apple devices. There is no first-party way to share files or clipboard content between an Android phone and a Mac with the same immediacy and low friction. Existing alternatives (email, chat apps, cloud drives, cable transfers) are slower and break the "just share it" workflow. Apple also offers Universal Clipboard between its own devices; there is no equivalent between Android and macOS.

## Goals

- Share files, text, and links between an Android phone and a Mac as easily as AirDrop.
- Automatic, bidirectional clipboard sync between the phone and paired Macs — equivalent to Apple's Universal Clipboard. See [ADR-0002](../03-architecture/ADR-0002-automatic-clipboard-sync.md).
- Work both on the same local network and over the internet (e.g., phone on mobile data, Mac at home).
- Deliver to an offline/unreachable device without losing the transfer — queue it for pickup later.
- Feel native on each platform: appears in Android's Share Sheet, integrates with macOS Share/Services.

## Non-goals (for now)

- Replacing AirDrop for Apple-to-Apple transfers.
- Supporting platforms other than Android and macOS.
- Group/broadcast sharing to many devices at once (single recipient first).

## Intended users and devices

- Primary user: the project owner, across one Android phone and two Macs (a MacBook/Mac used daily, and a Mac mini that stays powered on).
- The Mac mini acts as an always-on node and, per [ADR-0001](../03-architecture/ADR-0001-transport-and-relay-architecture.md), as the relay/inbox for asynchronous delivery.

## Key scope decisions

- **Native apps, not cross-platform UI frameworks.** Android: Kotlin + Jetpack Compose. macOS: Swift + SwiftUI. See [ADR-0001](../03-architecture/ADR-0001-transport-and-relay-architecture.md) for the reasoning.
- **Tailscale as the network layer.** All devices join the same tailnet; no port forwarding, no public-facing relay infrastructure to run.
- **Mac mini as always-on relay.** Provides store-and-forward delivery when the target device is offline, and can be reused later for push-style notifications (e.g., via a self-hosted ntfy instance).
- **Clipboard sync is automatic, not a manual share action.** The Android app requests Accessibility Service permission to read clipboard changes made by any app, in background, and pushes them to paired Macs with no per-copy confirmation. See [ADR-0002](../03-architecture/ADR-0002-automatic-clipboard-sync.md) for the trade-offs this implies (privacy, battery, Google Play policy).

## Planned feature priority

1. Device discovery and pairing (QR code or PIN).
2. Send files and text/links, peer-to-peer when both devices are reachable.
3. Store-and-forward delivery through the Mac mini when the target is offline.
4. Automatic clipboard sync (Universal Clipboard equivalent).
5. Phone notification mirroring on the Mac (stretch goal).

## Related

- [Architecture](../03-architecture/README.md)
- [ADR-0001: Transport and relay architecture](../03-architecture/ADR-0001-transport-and-relay-architecture.md)
- [ADR-0002: Automatic clipboard sync](../03-architecture/ADR-0002-automatic-clipboard-sync.md)
