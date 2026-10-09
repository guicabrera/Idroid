# idroid-relay

The Mac mini store-and-forward relay from [ADR-0001](../docs/03-architecture/ADR-0001-transport-and-relay-architecture.md) and [ADR-0002](../docs/03-architecture/ADR-0002-automatic-clipboard-sync.md). It accepts uploads (files, text/links, clipboard pushes) for a recipient device and holds them until that device fetches them, bounded by a retention window — the fallback path for when a direct device-to-device connection over the tailnet isn't available.

It assumes it only runs behind Tailscale: there's no internet-facing hardening, rate limiting, or TLS termination here. Transport encryption comes from the tailnet (WireGuard); this service only adds per-device bearer tokens so that one paired device can't read or write another's inbox.

## API

All endpoints except `/healthz`, `/admin/devices`, and `/pair/*` require `Authorization: Bearer <device token>`.

| Method & path | Purpose |
|---|---|
| `GET /healthz` | Liveness check, no auth. |
| `POST /pair/start` | Begin a pairing session, no auth needed. Body: `{"name": "mac-mini"}`. Mints a device token for the calling app immediately (returned in `device`) and a short-lived 6-digit `pairing.code` (5 min) that the user reads aloud, types in, or otherwise hands to the other device — this is the actual in-app pairing flow, not a stand-in. Returns `{device: {id, name, token, created_at}, pairing: {session_id, code, expires_at}}`. |
| `POST /pair/claim` | The second device calls this with the code from `/pair/start`. Body: `{"code": "510445", "name": "phone"}`. Mints a token for the caller and consumes the code (one-time use; a replay gets `410 Gone`). Returns `{device: {id, name, token, created_at}, peer: {id, name}}` — `peer` is the *other* device, so both sides end up knowing each other's device id without any manual copy-pasting. |
| `GET /pair/status/{session_id}` | Polled by the device that called `/pair/start` to learn when the other side has claimed the code. Returns `{claimed: false}` until then, then `{claimed: true, peer: {id, name}}`. |
| `POST /admin/devices` | Mint a new device token out of band. Requires header `X-Admin-Secret: <secret>`. Body: `{"name": "phone"}`. Returns `{id, name, token, created_at}` — kept for scripting/operator use now that `/pair/start` + `/pair/claim` is the primary in-app pairing flow. |
| `POST /upload` | Multipart form. Fields: `recipient` (device id), `kind` (`file`\|`text`\|`clipboard`), optional `filename`, optional `content_type`; file part `payload`. Returns the created item's metadata. |
| `GET /inbox` | List pending items addressed to the authenticated device. |
| `GET /inbox/{id}/content` | Download an item's payload. 404s if the item doesn't belong to the authenticated device. |
| `DELETE /inbox/{id}` | Remove an item once picked up. |
| `GET /ws` | Upgrade to a websocket; the relay pushes `{"event":"new_item","item_id":"..."}` whenever a new item lands in that device's inbox. Browsers can't set custom headers during the handshake, so the token may also be passed as `?token=`. This is a latency optimization only — `GET /inbox` remains the source of truth if a push is missed. |

Items are deleted automatically once they are older than `IDROID_RETENTION_DAYS` (default 7), via a background sweep that runs hourly — whether or not they were ever picked up.

## Configuration

Read from environment variables (kept this way so the launchd plist can stay a static file with no secrets baked into a config file on disk):

| Variable | Default | Notes |
|---|---|---|
| `IDROID_ADMIN_SECRET` | — | Required. No fallback; the relay refuses to start without it. Generate with `openssl rand -hex 32`. |
| `IDROID_LISTEN_ADDR` | `:8443` | Prefer binding to the Mac mini's Tailscale interface IP specifically (`tailscale ip -4`) rather than `0.0.0.0`, so the relay is unreachable even if a router misconfiguration ever forwarded the port. |
| `IDROID_DATA_DIR` | `~/Library/Application Support/idroid-relay` | Where device records and item blobs are persisted. |
| `IDROID_RETENTION_DAYS` | `7` | How long an item is kept before the cleanup sweep deletes it. |

## Running locally

```sh
export IDROID_ADMIN_SECRET="$(openssl rand -hex 32)"
go run ./cmd/idroid-relay
```

## Installing on the Mac mini as a launchd agent

1. Build a binary and put it on `PATH`:

   ```sh
   go build -o /usr/local/bin/idroid-relay ./cmd/idroid-relay
   ```

2. Copy the plist and fill in a real admin secret:

   ```sh
   cp launchd/com.idroid.relay.plist ~/Library/LaunchAgents/com.idroid.relay.plist
   # edit ~/Library/LaunchAgents/com.idroid.relay.plist:
   #   - replace REPLACE_WITH_A_GENERATED_SECRET with `openssl rand -hex 32`
   #   - set IDROID_LISTEN_ADDR to the Mac mini's tailnet IP (`tailscale ip -4`) + :8443
   ```

   This installs it as a **per-user LaunchAgent** (runs while the Mac mini's account is logged in), which is the simplest option for a machine that stays logged in — no root, no system-level data directory permissions to manage. If the Mac mini should run it even when no one is logged in, install the same plist as a LaunchDaemon in `/Library/LaunchDaemons` instead, running as a dedicated user, and point `IDROID_DATA_DIR` at a path that user owns.

3. Load it:

   ```sh
   launchctl load ~/Library/LaunchAgents/com.idroid.relay.plist
   ```

4. Check it's up (from the Mac mini itself, or from another tailnet device once you've confirmed the bound address):

   ```sh
   curl http://127.0.0.1:8443/healthz
   ```

5. From here, pairing the client apps to each other is in-app (see Android/macOS READMEs): one app calls `/pair/start` and shows a 6-digit code, the other calls `/pair/claim` with it. `POST /admin/devices` below is only needed for scripting or if you want to mint a token out of band instead:

   ```sh
   curl -X POST http://127.0.0.1:8443/admin/devices \
     -H "X-Admin-Secret: <the secret from the plist>" \
     -H "Content-Type: application/json" \
     -d '{"name": "guilherme-phone"}'
   ```

Logs: `/tmp/idroid-relay.log` and `/tmp/idroid-relay.err.log` (see `StandardOutPath`/`StandardErrorPath` in the plist).

## What's deliberately out of scope here

- **QR code pairing.** The PIN flow (`/pair/start` + `/pair/claim`) is implemented and is what the apps use today; encoding the same code into a QR image (for the initiator to display) and scanning it (camera access on Android, `AVFoundation`/`CIQRCodeGenerator` on macOS) is still open, per ADR-0001's feature priority list.
- **Per-app clipboard exclusion list** from ADR-0002 — the relay just stores whatever `kind=clipboard` payload it's given; filtering sensitive clipboard content happens on the Android side before it's ever uploaded.
- **TLS** — relies entirely on the tailnet being the trust boundary. If the relay is ever exposed outside Tailscale, add TLS (e.g. via `tailscale serve`) before that happens.
