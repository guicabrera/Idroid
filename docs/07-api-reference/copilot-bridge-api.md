---
tags:
  - idroid
  - documentation
  - api
created: 2026-11-24
modified: 2026-11-24
status: active
---

# Copilot bridge API

> The Idroid Bridge Obsidian plugin's local HTTP API: Copilot agent chats and VS Code for the phone ([ADR-0006](../03-architecture/ADR-0006-copilot-bridge.md), [ADR-0007](../03-architecture/ADR-0007-vscode-on-phone.md)).

## Overview

Source: `obsidian-bridge/` (installed as `.obsidian/plugins/idroid-bridge`). On load it serves HTTP on a random `127.0.0.1` port and writes:

- `~/.idroid/copilot-bridge.json` (`0600`): `{port, token, pid}` (`pid` = Obsidian's renderer; removed on unload, left stale by a crash).
- `~/.idroid/copilot`: helper, `copilot <METHOD> <PATH> [JSON body]`. If the info file is missing, its `pid` is dead or the port refuses the connection, it runs `obsidian-watchdog ensure` (restarts Obsidian, waits up to 90 s for the bridge) and retries once; exit 3 if Obsidian doesn't come back.
- `~/.idroid/obsidian-watchdog`: copy of `obsidian-bridge/watchdog.sh` (see the [release guide](../05-deployment/release-guide.md#idroid-bridge-obsidian-plugin)).

The phone runs the helper through [`exec`](./remote-commands-api.md), passing the body as `$1`.

## Authentication and authorization

`Authorization: Bearer <token>` from the info file (new random token per plugin load). Localhost only.

## Endpoints

| Method & path | Body | Returns |
|---------------|------|---------|
| `GET /state?session=&since=&wait=` | — | `{version, sessions[], session}`. With `wait` (s, max 50) and `since` = last `version`, waits for a change. `session` = `{id, status, messages[{id, sender, text, tools[{title, status, detail}], error}], permissions[{toolCallId, title, detail, options[{id, name, kind}]}]}`; default session = the one open on the Mac. |
| `POST /send` | `{session?, text}` | `{session}`. Without `session`, creates a chat. Doesn't switch the Mac's open chat for existing ones. |
| `POST /permission` | `{session, toolCallId, optionId}` | `{}` |
| `POST /cancel` | `{session}` | `{}` |
| `POST /open` | `{session}` | `{}` — makes it the open chat on the Mac. |
| `POST /close` | `{session}` | `{closing}` — the phone closed the tab (or the Mac's remote window). Only chats created through this bridge's `/send` are closed, after their turn and never while open on the Mac; `closing: false` = not one of them. |

## Cleanup

Every 30 s the plugin closes chats it created that were asked to close or haven't changed for 30 min (same rules as `/close`; they stay in the history and can be resumed), and turns `tailscale serve --https=8445` off once no phone VS Code window has been connected for 10 min (`POST /vscode` turns it on again). Crashes and memory: `obsidian-watchdog` (release guide).
| `GET /history` | — | `{chats[{path, title, lastUsed (epoch ms), backend}]}` — saved agent chats, newest first (100). |
| `POST /resume` | `{path}` | `{session}` — reopens a saved chat (as the Navigator does). |
| `POST /vscode` | `{folder?}` | `{url, folder, vault, home}` — starts VS Code Pane's server, runs `tailscale serve --bg --https=8445`, URL opens `folder` (default: vault). |

## Errors

`401` bad token; `404` unknown session/saved chat; `500` with `{error}` (e.g. Copilot agent chat not running, VS Code Pane not enabled). The helper exits `3` when the info file is missing (Obsidian closed).

## Operational notes

- Uses Copilot internals (`agentSessionManager`, `getChatHistoryItems`, `loadChatById`), checked on Copilot 4.0.18. Re-test after Copilot updates: `~/.idroid/copilot GET /state`.
- Changes are detected by a 400 ms fingerprint of sessions, messages, status and pending permissions.

## Related documentation

- [Obsidian Copilot on the phone](../06-features/copilot-on-phone-spec.md)
- [VS Code on the phone](../06-features/vscode-on-phone-spec.md)
