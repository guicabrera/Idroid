# Idroid — macOS app

Swift + SwiftUI client for [ADR-0001](../docs/03-architecture/ADR-0001-transport-and-relay-architecture.md): pairs with the relay (see [relay/README.md](../relay/README.md)), sends files/text, lists/downloads/deletes items in the inbox, and keeps a websocket open in the background (via a menu bar presence) so incoming items push in instead of requiring a manual refresh.

## What's in this first increment

- **Pairing** (`PairingView.swift` / `PairingStore.swift`): three ways in, per [relay/README.md](../relay/README.md#api) — "Start pairing" generates a 6-digit code on this device and polls until the other device claims it; "Join with code" enters a code shown by the other device; "Manual" is the old fallback of pasting values minted via `POST /admin/devices`, kept for scripting. Pairing config (including the device token) is stored in the macOS Keychain. QR code pairing (encoding the same code into an image) is still open per the Project Vision's feature priority list.
- **Home view** (`HomeView.swift`): send a quick text/link, pick a file to send (`NSOpenPanel`), and list/download/delete whatever is sitting in this device's relay inbox.
- **Always-on push** (`RelaySyncController.swift` + the `MenuBarExtra` in `IdroidApp.swift`): holds the relay's websocket open and refreshes the inbox (and posts a system notification, when running as a packaged app — see below) whenever something new lands, instead of relying on polling. The menu bar icon is the macOS equivalent of the Android foreground service's persistent notification: a visible reminder that sync is live.
- **Automatic clipboard sync** (ADR-0002): `ClipboardSyncController.swift` polls `NSPasteboard.general.changeCount` once a second (no special permission needed on macOS, unlike Android) and pushes new text/link copies to the paired device as `kind=clipboard` items. `AppState.refreshInbox` applies incoming clipboard pushes straight to this Mac's pasteboard and deletes them from the relay — they never show up as inbox entries. Copies tagged with the de facto `org.nspasteboard.ConcealedType` marker (several password managers set this) are never synced. Echo-loop prevention: `noteAppliedRemoteContent` records the change count and content hash right after applying a remote push, so the next poll tick recognizes its own echo instead of re-uploading it.

## What's deliberately not here yet

- **QR code pairing** — the PIN flow is implemented (see above); encoding the code into a scannable QR image is still open.
- **Share/Services menu integration** — sending is in-app only for now (pick a file via the Send File… button); hooking into macOS's Share menu/Services so other apps can send to Idroid directly is future work.
- **Direct peer-to-peer connection** — per ADR-0001 the design is "try a direct tailnet connection first, fall back to the relay," but this first increment (like the Android client) only talks to the relay.

## Building and running

Requires Xcode's command-line tools (for the Swift toolchain) — no full Xcode project needed for this increment, it's a Swift Package:

```sh
swift build        # compile
swift run           # build and launch
```

This opens a normal SwiftUI window plus a menu bar item. **Known limitation of running it this way:** a bare `swift run` binary has no bundle identifier, so `UNUserNotificationCenter` (system notifications for new items) is skipped rather than crashing — the inbox still refreshes automatically over the websocket, you just won't get a banner. To get real system notifications, package this target as a proper `.app` via Xcode (`File > New > Project`, or wrap it with a minimal `Info.plist` and `.app` bundle structure) — not done yet, same "built and tested from the command line only" caveat the Android app currently has.

## Pairing walkthrough

1. Install and start the relay on your Mac mini (see [relay/README.md](../relay/README.md)).
2. On this Mac, open the "Start pairing" tab, enter the relay's base URL and a name for this device, and tap Generate code.
3. On the other device (phone or another Mac), open its "Join with code" screen, enter the same relay base URL, a name for that device, and the 6-digit code shown on this Mac.
4. Both devices finish pairing automatically — this Mac's "Start pairing" screen is polling in the background and saves the pairing as soon as the other device claims the code.

Falling back to `POST /admin/devices` + the "Manual" tab still works if you'd rather script it (see [relay/README.md#api](../relay/README.md#api)).
