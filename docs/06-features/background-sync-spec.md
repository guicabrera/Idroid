---
tags:
  - idroid
  - documentation
  - features
created: 2026-11-24
modified: 2026-11-24
status: active
---

# Background sync on Android

> **Purpose:** How the phone stays connected to the relay and comes back on its own.

## Overview

`RelaySyncService` is a foreground service holding the relay websocket (pushes, clipboard, fs requests). It must survive the app being swiped from recents, reboots and updates.

## Behavior

| Situation | What brings the service back |
|-----------|------------------------------|
| App swiped out of recents | `onTaskRemoved` schedules an alarm that restarts it in ~3 s (One UI kills the process with the task and `START_STICKY` often doesn't return). |
| Killed by the system | `onDestroy` schedules the same alarm (only while paired). |
| Reboot / app update | `BootReceiver` (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`). |
| Process died | `START_STICKY`. |

- Service type `specialUse`, not `dataSync`: Android 15 caps `dataSync` at 6 h/day and forbids starting it at boot.
- Starting a foreground service from an alarm or at boot needs the **battery optimization exemption**; the home screen shows a card ("Allow background use") until it is granted.
- **Force stop** clears the alarms: the app must be opened again.

## Related documentation

- [Release guide](../05-deployment/release-guide.md)
