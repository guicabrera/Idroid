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

Use the [ADR template](../10-resources/templates/adr-template.md) for decisions. Keep ADRs in this folder while there are fewer than three; create an `adrs/` subfolder when the collection grows.

## Related

- [Development](../04-development/README.md)
- [Deployment](../05-deployment/README.md)
- [ADR template](../10-resources/templates/adr-template.md)
