# Idroid — Android app

Kotlin + Jetpack Compose client for [ADR-0001](../docs/03-architecture/ADR-0001-transport-and-relay-architecture.md): pairs with the relay (see [relay/README.md](../relay/README.md)), sends files/text through Android's native Share Sheet, lists/downloads/deletes items in the inbox, and keeps a foreground service running so incoming items push in instead of requiring a manual refresh.

## What's in this first increment

- **Pairing** (`PairingScreen.kt` / `PairingRepository.kt`): three ways in, per [relay/README.md](../relay/README.md#api) — "Start pairing" generates a 6-digit code on this phone and polls until the other device claims it; "Join with code" enters a code shown by the other device; "Manual" is the old fallback of pasting values minted via `POST /admin/devices`, kept for scripting. Pairing config (including the device token) is stored in `EncryptedSharedPreferences`. QR code pairing (encoding the same code into a scannable image) is still open per the Project Vision's feature priority list.
- **Share Sheet target** (`ShareReceiverActivity.kt`): registers for `ACTION_SEND` / `ACTION_SEND_MULTIPLE` so "Idroid" shows up like any other app in Android's share menu, for both files and plain text/links.
- **Home screen** (`HomeScreen.kt`): send a quick text/link, pick a file to send, and list/download/delete whatever is sitting in this device's relay inbox.
- **Always-on push** (`RelaySyncService.kt`): a foreground service (with a persistent low-priority notification) that holds the relay's websocket open and raises a notification when something new lands, instead of relying on polling.
- **Automatic clipboard sync** (ADR-0002): `ClipboardAccessibilityService.kt` registers a clipboard listener (its only purpose — it never inspects accessibility events or the UI tree) so copies made by *any* app, in the background, get pushed to the paired device as `kind=clipboard` items. `RelaySyncService` applies incoming clipboard pushes to this phone's clipboard and deletes them from the relay — they never show up as inbox entries (filtered out in `IdroidViewModel.refreshInbox`). Content flagged `ClipDescription.EXTRA_IS_SENSITIVE` (password managers, OTP autofill) is never synced. Echo-loop prevention: `IdroidApplication.clipboardSyncState` tracks the hash of the last value this device *applied* from a remote push, so the listener recognizes its own echo instead of re-uploading it. The Accessibility Service can't be enabled programmatically — `HomeScreen.kt` shows a banner linking to Settings until the user turns it on once.

## What's deliberately not here yet

- **QR code pairing** — the PIN flow is implemented (see above); encoding the code into a scannable QR image, and scanning one with the camera, is still open.

## Building

Requires a JDK (17+) and the Android SDK (platform 35, build-tools 35.0.0, platform-tools). If you don't have Android Studio installed, the command-line path is:

```sh
brew install openjdk@21
brew install --cask android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
yes | sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0"
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties
```

Then, from this directory:

```sh
./gradlew :app:assembleDebug   # build a debug APK
./gradlew :app:lintDebug       # Android lint
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Opening the `android/` folder directly in Android Studio works too, and is the easier path for day-to-day work (run on a device/emulator, inspect layouts, etc.) — this project was only built and lint-checked from the command line so far, not run on an actual device or emulator yet.

## Pairing walkthrough

1. Install and start the relay on your Mac mini (see [relay/README.md](../relay/README.md)).
2. On the Mac app (or any other device), open "Start pairing", enter the relay's base URL and a name, and generate a code.
3. On this phone, open "Join with code", enter the same relay base URL, a name for the phone, and that code.
4. Both sides finish pairing automatically — the device that started it is polling in the background and saves the pairing as soon as this phone claims the code.
5. Grant the notification permission prompt (needed for the incoming-item and sync-active notifications).

Falling back to `POST /admin/devices` + the "Manual" tab still works if you'd rather script it (see [relay/README.md#api](../relay/README.md#api)).
