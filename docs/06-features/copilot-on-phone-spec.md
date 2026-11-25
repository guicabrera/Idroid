---
tags:
  - idroid
  - documentation
  - features
created: 2026-11-24
modified: 2026-11-24
status: active
---

# Obsidian Copilot on the phone

> **Purpose:** Using the Mac's Obsidian Copilot agent chat — with its tools, permission prompts and history — from the phone.

## Overview

The Copilot plugin doesn't run agent chat on mobile. The Idroid Bridge plugin in Obsidian on the Mac drives Copilot's live chats, and the phone's **Copilot** screen (robot icon on the terminal screen) mirrors them ([ADR-0006](../03-architecture/ADR-0006-copilot-bridge.md)). Messages typed on the phone land in the real Copilot chat on the Mac.

## Behavior

- **Conversations:** the title menu lists the open Copilot chats; **+** starts a new one (it also shows on the Mac).
- **Saved chats…:** Copilot's saved agent chats, most recent first; picking one reopens it as the Copilot Navigator does — which also makes it the open chat on the Mac.
- Sending to an open chat doesn't change what the Mac shows.
- Replies update as the chat changes (long-poll), with one line per tool call (✓ / ✗ / …).
- **Permission prompts** show as cards with Copilot's own options; **Stop** cancels the turn.

## Requirements and limits

- Obsidian must be open on the Mac, with Copilot and Idroid Bridge enabled.
- Text arrives in chunks as the chat changes, not token by token.
- Built on Copilot internals (checked on Copilot 4.0.18): a Copilot update can break it; the phone then shows the error.
- Don't continue the same conversation from the phone's Claude Code mode and the Copilot at the same time.

## Related documentation

- [ADR-0006: Copilot bridge](../03-architecture/ADR-0006-copilot-bridge.md)
- [Copilot bridge API](../07-api-reference/copilot-bridge-api.md)
