---
tags:
  - idroid
  - documentation
  - features
created: 2026-11-24
modified: 2026-11-24
status: active
---

# VS Code on the phone

> **Purpose:** Opening the VS Code that runs in Obsidian's VS Code Pane plugin from the phone.

## Overview

**+ → VS Code** opens a tab with the VS Code Pane's VS Code web (same extensions and settings), reached over Tailscale in HTTPS ([ADR-0007](../03-architecture/ADR-0007-vscode-on-phone.md)).

## Behavior

- Opens the **Obsidian vault** folder by default. The folder button above the page switches to the vault, home (`~`) or any path on the Mac; VS Code's File → Open Folder also works.
- The Idroid Bridge starts VS Code Pane's server if no pane is open and publishes its proxy with `tailscale serve --https=8445` (tailnet only).
- **One editor group on the phone:** when the phone's window connects, the bridge gives it (and only it — VS Code web keeps settings per browser) `claudeCode.lockEditorGroups: false` and a hidden secondary side bar, and joins split groups. Files then open as tabs next to Claude Code instead of splitting the screen. Phone windows are recognized by their host: VS Code Pane's bridge extension (0.7.1+) reports `vscode.env.remoteAuthority`, which is `127.0.0.1:<port>` for Obsidian's panes and the tailnet name for the phone. In Restricted Mode extensions (the bridge included) don't run: tap **Trust** once.
- Back goes through VS Code's history, then closes the tab. Landscape or a tablet is much more comfortable.

## Requirements and limits

- Obsidian open on the Mac with VS Code Pane and Idroid Bridge enabled; Tailscale on, on the phone.
- The URL's secret prefix is its only credential on the tailnet. Regenerating VS Code Pane's token rotates it.

## Related documentation

- [ADR-0007](../03-architecture/ADR-0007-vscode-on-phone.md)
- [Copilot bridge API](../07-api-reference/copilot-bridge-api.md) (`POST /vscode`)
