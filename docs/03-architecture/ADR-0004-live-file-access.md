---
tags:
  - idroid
  - architecture
  - adr
created: 2026-11-21
modified: 2026-11-22
decision_date: 2026-11-22
status: accepted
deciders: [Guilherme Cabrera]
supersedes:
superseded_by:
---

# ADR-0004: Live file access between Android and macOS

> **Summary:** Idroid adds live, browse-on-demand file access in both directions (Mac folders on Android, Android folders on the Mac). Requests are tunneled through the relay over each app's existing websocket and streamed straight through without being stored; neither app accepts inbound connections. A direct tailnet path and an Android `DocumentsProvider` remain possible later steps.

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
- **Drawbacks:** New server code in both apps; each app must accept inbound connections and learn the other's tailnet address; Android serving requires a visible foreground service; Finder integration is expensive (see consequences).

### Option 4: Tunnel live requests through the relay websocket, stream without storing (chosen)

Both apps already keep a websocket open to the relay (`RelaySyncService` on Android, `RelaySyncController` on macOS). The caller asks the relay for a listing or a file; the relay pushes an `fs_request` to the target over that websocket; the target answers with an HTTP POST whose body the relay copies straight into the caller's response.

- **Benefits:** No server, port, or address discovery in either app; works whenever both devices can reach the relay, which they already must; group membership on the relay is the authorization, the same as for sends; nothing is written to the Mac mini's disk, which removes Option 2's main drawback.
- **Drawbacks:** Bytes still take the extra hop through the Mac mini, so a large file between two devices away from home crosses the home uplink twice; the target must be online (same as Option 3).

## Decision

**Chosen option: Option 4 — tunnel through the relay websocket, stream without storing**, in both directions at once, with an in-app browser on each platform.

Option 4 delivers both directions with a fraction of Option 3's code, because the always-on connection it needs already exists in both apps. Option 2 is rejected as written (it stores or mirrors files on the Mac mini); Option 4 keeps its single-endpoint simplicity without that cost. Option 3 stays the upgrade path if the extra hop becomes a problem for large transfers: the `fs_request` protocol and the listing JSON would be reused unchanged, only the transport would change.

## Consequences

- **Positive:** Both directions ship together, with no new listener in either app.
- **Positive:** Exposure is limited to explicitly shared folders, read-only.
- **Positive:** Nothing browsed is persisted on the relay.
- **Trade-off or risk:** Idroid now has two transfer models: async push (relay inbox) and live pull (tunneled). The UI shows when a device is offline and browsing is unavailable.
- **Trade-off or risk:** Every byte passes through the Mac mini; throughput is bounded by its links. Acceptable for documents and photos; revisit with Option 3 if video-sized transfers become common.
- **Trade-off or risk:** Any device in the group can browse every shared folder; there are no per-device grants yet.
- **Trade-off or risk:** On Android, only SAF trees the user granted are reachable; `MANAGE_EXTERNAL_STORAGE` is not requested. Serving rides on the existing `RelaySyncService` foreground service, so no extra notification is needed.
- **Trade-off or risk:** Mac files are only visible inside the Idroid app on Android, not yet in the system file picker (`DocumentsProvider`).
- **Trade-off or risk:** A Finder-native view of the phone requires either mounting WebDAV (`⌘K`, works, but stalls Finder when the phone drops off) or a File Provider extension (needs an app extension target and proper code signing, which the current SwiftPM + ad-hoc `codesign` build in `macos/scripts/bundle.sh` does not support). Deferred.

## Implementation notes

- **Relay** (`relay/internal/relay/fsproxy.go`): `GET /fs/{device_id}/list?path=…` and `GET /fs/{device_id}/content?path=…`, authenticated; the target must be in the caller's group (404 otherwise) and online (503). The relay pushes `{"event":"fs_request","request_id","op","path","sender_id"}` to the target and waits up to 20 s for it to start answering (504). The target answers with `POST /fs/respond/{request_id}` carrying `X-Idroid-Status`, `Content-Type` and the body; only the target device may answer. Nothing is written to disk.
- **Paths** are virtual: `/` lists the shared folders, `/<share>/sub/file` addresses a file. Each share is named after its folder, suffixed ` 2`, ` 3`… on clashes. Servers reject `.` and `..` segments; macOS resolves symlinks and checks the result is still inside the share.
- **Listing JSON:** `{"entries":[{"name","path","is_dir","size","modified","mime"}]}`; `modified` is RFC 3339, folders first, hidden files skipped.
- **macOS:** shared folders are picked in Settings → Sharing (`SharedFoldersStore`, plain paths since the app is not sandboxed); `FileShareResponder` answers requests; the Files section of the main window browses other devices (open, save, save to…).
- **Android:** shared folders are SAF trees from `ACTION_OPEN_DOCUMENT_TREE` with a persisted read grant (`SharedFoldersRepository`); `FileShareResponder` walks them with `DocumentsContract`; `FileBrowserScreen` browses other devices (tap to open, download button to save).
- **Out of scope:** writes (upload into a remote folder, rename, delete), folder downloads, thumbnails, `DocumentsProvider` / Finder integration, two-way sync, offline caching.

## Links

- Related ADRs: [ADR-0001](./ADR-0001-transport-and-relay-architecture.md), [ADR-0003](./ADR-0003-remote-actions-on-mac.md)
- Issue or ticket: none yet.
- References: [Android `DocumentsProvider`](https://developer.android.com/reference/android/provider/DocumentsProvider), [Apple File Provider](https://developer.apple.com/documentation/fileprovider), prior art — KDE Connect "Filesystem expose", LocalSend.

## Status history

| Date | Status | Notes |
|------|--------|-------|
| 2026-11-21 | proposed | Initial draft. |
| 2026-11-22 | accepted | Chose relay tunneling (Option 4) over direct peer servers; both directions implemented with in-app browsers. |
