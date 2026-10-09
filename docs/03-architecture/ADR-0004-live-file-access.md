---
tags:
  - idroid
  - architecture
  - adr
created: 2026-11-21
modified: 2026-11-21
decision_date:
status: proposed
deciders: [Guilherme Cabrera]
supersedes:
superseded_by:
---

# ADR-0004: Live file access between Android and macOS

> **Summary:** Idroid adds live, browse-on-demand file access as a direct device-to-device connection over the tailnet: first Mac folders exposed to Android through a `DocumentsProvider`, later Android folders exposed to the Mac; the relay inbox stays for push-style sends only.

## Context and problem

Today Idroid pushes things: a file or text is uploaded to the Mac mini relay and the recipient fetches it from its inbox ([ADR-0001](./ADR-0001-transport-and-relay-architecture.md)). A different need is pull: from the phone, open a folder on the Mac and pick a file (and the reverse) without having pushed it beforehand.

This changes the requirements:

- **The peer must be online.** Browsing a folder only makes sense against the live device; there is nothing to queue.
- **Data volume and latency.** Browsing means many small requests (listings, thumbnails) and on-demand downloads. Routing every byte through the Mac mini adds a hop and disk I/O for no benefit when both peers are reachable on the tailnet.
- **The two directions are not symmetric.** macOS can run a long-lived server in the menu bar app and already has SMB built in. Android limits background work (foreground service required), restricts file system access (scoped storage), and has no built-in file server.
- **Exposure.** Whatever the token can reach is what a stolen token can read or overwrite.

## Decision drivers

- Should feel native: Mac files should show up where Android apps already pick files, not only inside the Idroid app.
- Must expose only folders the user explicitly shared, read-only by default.
- Must not keep copies of browsed files on the relay.
- Must work across networks (tailnet), not only on the LAN.
- Android battery cost must stay acceptable; no always-on server unless the user enables it.
- Prefer incremental delivery: the cheaper, higher-value direction first.

## Options considered

### Option 1: No Idroid feature; document SMB / third-party apps

macOS File Sharing (SMB) reachable by MagicDNS name, browsed with Material Files or CX File Explorer; for the reverse, a WebDAV/FTP server app on Android.

- **Benefits:** Zero code; works today.
- **Drawbacks:** SMB exposes the Mac user's credentials and home folder rather than a scoped share; separate apps and separate auth from Idroid pairing; no system file picker integration; the Android server apps vary in quality and battery behavior.

### Option 2: Route browsing through the relay

Extend the relay with list/fetch endpoints that proxy to the peer, or sync folders to the Mac mini.

- **Benefits:** One endpoint for clients; reuses relay auth.
- **Drawbacks:** Extra hop and storage for every byte; Mac mini becomes a mirror of private folders; still needs the peer online for listings, so the relay adds little.

### Option 3: Direct peer file service over the tailnet, platform-native surfaces (chosen)

Each app can run a small authenticated HTTP file service bound to its tailnet interface, exposing only user-selected folders. The relay is used only for discovery (which address/port a paired device is serving on, and whether it is online) and for the pairing credentials.

- **Mac → Android:** the macOS app serves the shared folders. The Android app implements a `DocumentsProvider`, so "MacBook" and "Mac mini" appear as roots in the system file picker (`ACTION_OPEN_DOCUMENT`, Files app, any app's "attach" dialog).
- **Android → Mac:** the Android app serves SAF-granted folders from a foreground service started on demand. The Mac first gets a browser inside the Idroid app; a Finder-visible mount (WebDAV mount or File Provider extension) is a later step.

- **Benefits:** No extra hop; native integration on Android; scope limited to granted folders; reuses pairing tokens; direction 1 is useful on its own.
- **Drawbacks:** New server code in both apps; Android serving requires a visible foreground service; Finder integration is expensive (see consequences).

## Decision

**Chosen option: Option 3 — direct peer file service over the tailnet**, delivered in two phases:

1. **Phase 1: Mac folders on Android** via a Mac-side file service plus an Android `DocumentsProvider`.
2. **Phase 2: Android folders on the Mac**, starting with an in-app browser on macOS; Finder integration only if phase 2 proves useful day to day.

Option 1 remains the documented workaround until phase 1 ships. Option 2 is rejected because relaying adds cost without removing the online-peer requirement.

## Consequences

- **Positive:** Mac files become available to every Android app through the system picker, which is the most native outcome available.
- **Positive:** Exposure is limited to explicitly shared folders, per paired device, read-only unless write is enabled.
- **Positive:** The relay stays small; it gains presence/discovery, not file traffic.
- **Trade-off or risk:** Idroid now has two transfer models: async push (relay inbox) and live pull (direct). UI must make clear when a device is offline and browsing is unavailable.
- **Trade-off or risk:** Direct connections mean each app accepts inbound connections on the tailnet; auth must be enforced by the app, not assumed from tailnet membership (same principle as ADR-0001).
- **Trade-off or risk:** Android serving needs a foreground service with a persistent notification and SAF tree grants; folders outside granted trees are unreachable unless `MANAGE_EXTERNAL_STORAGE` is requested, which this ADR does not adopt.
- **Trade-off or risk:** A Finder-native view of the phone requires either mounting WebDAV (`⌘K`, works, but stalls Finder when the phone drops off) or a File Provider extension (needs an app extension target and proper code signing, which the current SwiftPM + ad-hoc `codesign` build in `macos/scripts/bundle.sh` does not support). Deferred.

## Implementation notes

- **Presence and discovery:** each device reports its file service endpoint (tailnet hostname + port) to the relay when it opens `/ws`; `GET /devices` returns it along with online status. No file data goes to the relay.
- **Auth:** requests carry the caller's device token; the serving device validates it against its local list of paired peers (obtained at pairing / from `GET /devices`), not by calling the relay on every request. Rotate/revoke follows device removal (`DELETE /devices/{id}`).
- **Binding:** listen only on the Tailscale interface (100.64.0.0/10 / `fd7a:115c:a1e0::/48`), never `0.0.0.0`.
- **API sketch (both platforms):**
  - `GET /fs/roots` — shared roots with display name and `writable` flag.
  - `GET /fs/list?root=<id>&path=<rel>` — entries (name, size, mtime, mime, is_dir).
  - `GET /fs/content?root=<id>&path=<rel>` — file bytes, with `Range` support.
  - `GET /fs/thumbnail?...` — optional, for the picker.
  - `PUT /fs/content?...` — only when the root is writable.
  - Paths are relative to a root; reject `..`, absolute paths, and symlinks that resolve outside the root.
- **macOS:** shares are folders picked with `NSOpenPanel` and stored as security-scoped bookmarks (the same pattern as `SaveLocationStore`), so the design survives a future move to a sandboxed build.
- **Android phase 1:** `DocumentsProvider` with one root per online paired Mac; `queryChildDocuments` maps to `/fs/list`, `openDocument` streams `/fs/content` through a pipe or cached file; notify roots changed when presence changes.
- **Android phase 2:** shared folders are SAF trees from `ACTION_OPEN_DOCUMENT_TREE` (already used by `SaveLocationRepository`); serving runs in a foreground service the user starts from the app or a quick settings tile, with an idle timeout.
- **Out of scope:** two-way folder sync, conflict resolution, offline caching of remote folders.

## Links

- Related ADRs: [ADR-0001](./ADR-0001-transport-and-relay-architecture.md), [ADR-0003](./ADR-0003-remote-actions-on-mac.md)
- Issue or ticket: none yet.
- References: [Android `DocumentsProvider`](https://developer.android.com/reference/android/provider/DocumentsProvider), [Apple File Provider](https://developer.apple.com/documentation/fileprovider), prior art — KDE Connect "Filesystem expose", LocalSend.

## Status history

| Date | Status | Notes |
|------|--------|-------|
| 2026-11-21 | proposed | Initial draft. |
