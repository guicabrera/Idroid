---
tags:
  - idroid
  - documentation
  - deployment
created: 2026-11-24
modified: 2026-11-24
status: active
---

# Release guide

> **Purpose:** How each Idroid component is built, released and installed.

## Overview

| Component | Where it runs | How it ships |
|-----------|---------------|--------------|
| Android app | Phone | GitHub Release with the APK (`scripts/release-android.sh`) |
| macOS app | Each Mac | `macos/scripts/bundle.sh --install` |
| Relay | Mac mini (podman) | `podman compose up -d --build --force-recreate` |
| Idroid Bridge | Obsidian on the Mac | Copy `obsidian-bridge/` into the vault's plugins and enable |

## Android

```bash
scripts/release-android.sh 0.8.3
```

1. Requires a clean tree; refuses an existing tag.
2. Bumps `versionCode`/`versionName`, builds `assembleDebug` + `lintDebug` (JDK 21: the script sets `JAVA_HOME` to `openjdk@21`).
3. Commits `release: android-vX`, tags `android-vX`, pushes.
4. Creates the GitHub Release `Idroid Android vX` with `Idroid-android-vX.apk` and notes from the commit subjects since the previous `android-v*` tag.

The APK is signed with the debug key (`~/.android/debug.keystore`): it updates over earlier releases; changing keys means uninstalling first.

## macOS app

```bash
cd macos
SDKROOT=/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk ./scripts/bundle.sh --install
```

Builds `Idroid.app`, copies it to `/Applications` and reopens it.

## Relay

```bash
cd relay && go test ./... && podman compose up -d --build --force-recreate
curl http://<tailnet name>:8443/healthz
```

## Idroid Bridge (Obsidian plugin)

```bash
cp obsidian-bridge/{manifest.json,main.js} <vault>/.obsidian/plugins/idroid-bridge/
```

Enable it in Obsidian (Community plugins). After editing, bump `manifest.json`'s version and disable/enable the plugin. Check: `~/.idroid/copilot GET /state`.

## Related documentation

- [Background sync on Android](../06-features/background-sync-spec.md)
- [Relay README](../../relay/README.md)
