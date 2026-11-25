---
tags:
  - idroid
  - documentation
  - api
created: 2026-11-24
modified: 2026-11-24
status: active
---

# Remote commands API

> Commands, completion and the command catalog, tunneled by the relay to a Mac ([ADR-0005](../03-architecture/ADR-0005-remote-terminal-and-ai-agent.md)).

## Overview

These are ops of the relay's live fs tunnel ([ADR-0004](../03-architecture/ADR-0004-live-file-access.md), [relay README](../../relay/README.md#api)). The relay forwards each request to the target over its websocket as an `fs_request` and pipes the target's answer back; nothing is stored. The macOS app answers them in `CommandResponder.swift`.

## Authentication and authorization

`Authorization: Bearer <device token>`. Caller and target must be in the same device group. Whether a command runs is the Mac's decision: named commands always, free commands and completion only with **Allow terminal from paired devices** (on by default). Every run is logged to `~/Library/Logs/Idroid/commands.log` with the device name.

## Endpoints

### `GET /fs/{device_id}/commands`

The Mac's catalog.

```json
{
  "terminal_enabled": true,
  "home": "/Users/me",
  "actions": [{"id": "git-pull", "name": "git pull (folder)", "command": "git pull --ff-only", "takes_input": false, "group": "Dev"}],
  "ssh_host": "mac-mini.tailnet.ts.net",
  "user": "me",
  "remote_login": true,
  "vault": "/path/to/obsidian-vault"
}
```

### `PUT /fs/{device_id}/exec`

Body (JSON), either a free command or a named one:

```json
{"command": "ls -la", "cwd": "~/repos", "input": "optional, arrives as $1"}
{"action_id": "volume", "input": "40"}
```

Response `200 text/plain`, streamed while the command runs (the relay flushes each chunk), ending with a trailer line:

```
<output…>
\n\u001E{"exit":0,"cwd":"/Users/me/repos"}\n
```

- Runs `zsh -l -c` with the script in an environment variable (never spliced into a command line), stdin closed, `TERM=dumb`, 15 min timeout.
- The caller closing the connection terminates the command.

### `PUT /fs/{device_id}/complete`

Body `{"line": "git che", "cwd": "~"}` → `{"suggestions": ["checkout", …]}` (bash `compgen`: commands for the first word, paths after; folders end in `/`; up to 40).

## Errors

| Status | Meaning |
|--------|---------|
| `403` | Terminal turned off on the Mac, or unknown named command (plain-text reason). |
| `503` | Mac offline. Commands are never queued. |
| `504` | The Mac didn't start answering within 20 s. |

## Related documentation

- [Remote terminal](../06-features/remote-terminal-spec.md)
- [Copilot bridge API](./copilot-bridge-api.md) (called through `exec`)
