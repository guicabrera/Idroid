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

# ADR-0009: Phone screen mirror on the Mac

> **Summary:** The Mac opens a window with the phone's live screen and controls it with mouse and keyboard. The phone captures with MediaProjection, encodes H.264 and serves it over a direct TCP socket; signalling and input reuse the ADR-0008 pipe.

## Decision

- **Start:** Mac sends `mirror{on:"true"}` over the input pipe and opens a waiting window. The phone's `InputControlService` starts `MirrorConsentActivity` (Android's capture consent — required every session, no way around it without root/adb), which starts `MirrorService` (foreground, type `mediaProjection`).
- **Video:** VirtualDisplay → MediaCodec H.264 (long side ≤ 1600 px, 8 Mbps, 60 fps, low-latency, repeat frame after 100 ms). `ServerSocket` on a random port; the phone answers `mirror_ready{port,key,w,h}`. The Mac connects to the LAN/Tailscale addresses from `hello`, sends `key\n`, then reads `[int32 len][int64 pts][u8 flags][Annex B]` frames (codec config re-sent and a sync frame requested for each new client).
- **Mac:** `MirrorWindowController` parses Annex B, builds the format from SPS/PPS, enqueues AVCC samples into `AVSampleBufferDisplayLayer` (hardware decode, display immediately). Window keeps the phone's aspect ratio.
- **Input in the window:** `pos{x,y}` (normalized) + `down`/`up`, `scroll`, `rclick`, `text`, `key` — same handlers as ADR-0008, no overlay pointer.
- **Stop:** closing the window sends `mirror{on:"false"}`; the phone sends `mirror_stopped` when capture ends (or consent is refused).

## Consequences

- Consent prompt on the phone every time mirroring starts.
- Rotation isn't followed yet (capture size fixed at start); audio isn't mirrored.
- Needs the ADR-0008 pipe (Settings → Mouse on, phone ready) and the phone reachable directly (same LAN or Tailscale).

## Status history

| Date | Status | Notes |
|------|--------|-------|
| 2026-11-25 | accepted | Implemented in Android (MirrorService) and macOS (MirrorWindowController). |
