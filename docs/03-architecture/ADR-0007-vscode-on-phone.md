---
tags:
  - idroid
  - architecture
  - adr
created: 2026-11-24
modified: 2026-11-24
decision_date: 2026-11-24
status: accepted
deciders: [Guilherme Cabrera]
supersedes:
superseded_by:
---

# ADR-0007: VS Code on the phone through the VS Code Pane proxy

> **Summary:** The phone opens the VS Code that the VS Code Pane plugin runs in Obsidian (`code serve-web` behind its secret-prefix proxy), published on the tailnet over HTTPS with `tailscale serve`. It shows as a tab next to Terminal and SSH.

## Context and problem

VS Code Pane already runs VS Code web on this Mac: `code serve-web` on a unix socket, and a proxy on 127.0.0.1:18765 that only answers under a secret prefix and injects the connection token. The phone needs a way in that keeps those guarantees and works over Tailscale, not only on localhost.

## Decision

- **Reuse VS Code Pane's server and proxy** (chosen by the user over a separate `code serve-web` for the phone): same extensions, settings and bridge; Obsidian must be open.
- **Idroid Bridge `POST /vscode`:** calls `vscode-pane`'s `server.ensure()` (starts it if no pane is open), then `tailscale serve --bg --https=8445 http://127.0.0.1:<proxy port>` (idempotent, tailnet only) and returns `https://<magicdns>:8445/<prefix>/`.
- **HTTPS is required:** the proxy rewrites cookies to `Secure; SameSite=None` and VS Code web needs a secure context; Tailscale's certificate makes it valid.
- **Phone:** a WebView tab (JavaScript, DOM storage, zoom); Back walks VS Code's history, then closes the tab.

## Consequences

- **Positive:** the full VS Code from Obsidian on the phone, no new server.
- **Risk:** the secret prefix is the only credential on that URL, now reachable by every device on the tailnet that knows it. The phone gets it only through the authenticated command tunnel. Regenerating VS Code Pane's token (its settings) rotates it.
- **Limitation:** a phone screen is small for VS Code; landscape or a tablet works better.
- **Note:** this Mac can't reach its own tailnet name (macOS Tailscale hairpin); tested with `--resolve` to its Tailscale IP: HTTP 200 with a valid certificate.
