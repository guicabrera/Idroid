---
tags:
  - idroid
  - documentation
  - architecture
created: 2026-10-09
modified: 2026-10-09
status: active
---

# 03 — Architecture

> Describe Idroid's components, boundaries, data, and technical decisions.

## Contents

| Document | Description |
|-----------|-------------|
| [ADR-0001: Transport and relay architecture](./ADR-0001-transport-and-relay-architecture.md) | Tailscale tailnet + Mac mini relay for Android↔macOS sharing; native apps per platform. |
| [ADR-0002: Automatic clipboard sync](./ADR-0002-automatic-clipboard-sync.md) | Accessibility Service on Android for background clipboard capture; automatic, no-confirmation bidirectional sync. |
| [ADR-0003: Remote actions on the Mac](./ADR-0003-remote-actions-on-mac.md) | *Proposed.* Mac-side allowlist of actions (built-ins + approved Shortcuts) triggered live from Android; no arbitrary shell. |
| [ADR-0004: Live file access](./ADR-0004-live-file-access.md) | *Proposed.* Direct tailnet file service; Mac folders in Android's system picker first, Android folders on the Mac later. |
| [ADR-0005: Remote terminal and AI agent](./ADR-0005-remote-terminal-and-ai-agent.md) | *Accepted.* Named commands, opt-in free terminal with autocomplete and an approval-gated AI agent, over the ADR-0004 tunnel. |
| [ADR-0006: Copilot bridge](./ADR-0006-copilot-bridge.md) | *Accepted.* Obsidian plugin exposes Copilot's live agent chat to the phone over the command tunnel. |
| [ADR-0007: VS Code on the phone](./ADR-0007-vscode-on-phone.md) | *Accepted.* VS Code Pane's proxy published on the tailnet with tailscale serve, opened in a phone tab. |

Use the [ADR template](../10-resources/templates/adr-template.md) for decisions. Keep ADRs in this folder while there are fewer than three; create an `adrs/` subfolder when the collection grows.

## Related

- [Development](../04-development/README.md)
- [Deployment](../05-deployment/README.md)
- [ADR template](../10-resources/templates/adr-template.md)
