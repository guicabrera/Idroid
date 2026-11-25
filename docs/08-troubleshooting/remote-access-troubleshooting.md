---
tags:
  - idroid
  - documentation
  - troubleshooting
created: 2026-11-24
modified: 2026-11-24
status: active
severity: medium
---

# Troubleshooting: Terminal, SSH, Copilot and VS Code on the phone

> Diagnose the phone's remote features on a Mac.

## Symptoms

- A terminal, SSH, Copilot or VS Code tab shows an error, or the terminal button is greyed out.

## Quick checks

1. The Mac shows **Online** on the phone's home screen (the macOS app is running).
2. Tailscale is on on the phone (SSH and VS Code connect over the tailnet; the terminal and Copilot go through the relay).
3. Obsidian is open on the Mac (Copilot and VS Code only).

## Possible causes and resolution

| Symptom | Cause | Resolution |
|---------|-------|------------|
| "The terminal is turned off on …" | Free terminal disabled on the Mac | Mac: Idroid → Settings → Commands → Allow terminal. |
| `zsh: no matches found` on a sentence | A message sent in `$` mode | Switch the input chip to AI or Claude. |
| SSH: "Remote Login is off" | sshd not running | System Settings → General → Sharing → Remote Login, then **Check again**. |
| SSH: "Auth fail" | The phone's key isn't in `~/.ssh/authorized_keys` (removed, or the Mac's user changed) | **Try again**: after an auth failure the app authorizes its key again before connecting. |
| SSH: host key error | The Mac's SSH host key changed (e.g. macOS reinstalled) | Expected change only: clear the app's data (resets `known_hosts`; this also unpairs, so pair again). Otherwise don't connect. |
| SSH/VS Code: timeout | Phone not on the tailnet | Turn Tailscale on. |
| Copilot/VS Code: "Open Obsidian…" | Bridge info file missing | Open Obsidian; check Idroid Bridge is enabled. |
| Copilot/VS Code: `curl: (7) Failed to connect to 127.0.0.1` | Obsidian's renderer crashed (main process alive, no window), info file stale | `~/.idroid/obsidian-watchdog restart`; crash reports in `~/Library/Logs/DiagnosticReports/Obsidian Helper (Renderer)-*.ips`. With plugin ≥ 0.8.0 the helper and the watchdog do it themselves. |
| "Obsidian didn't come back on this Mac" | Restart failed (vault volume not mounted, plugin disabled) | `~/.idroid/obsidian-watchdog status` and its log `~/Library/Logs/Idroid/obsidian-watchdog.log`. |
| Copilot: errors after a Copilot update | Copilot internals changed | `~/.idroid/copilot GET /state` on the Mac; update `obsidian-bridge/main.js`. |
| VS Code: "VS Code Pane plugin isn't enabled" | Plugin off | Enable VS Code Pane in Obsidian. |
| App returned home and tabs were lost | Android killed the app in the background (rotation no longer does) | Reopen; tabs aren't persisted. |

Testing VS Code from the Mac itself: the Mac can't reach its own tailnet name (macOS Tailscale), use `curl --resolve <name>:8445:$(tailscale ip -4) <url>`.

## Prevention and related documents

- [Remote terminal](../06-features/remote-terminal-spec.md), [SSH terminal](../06-features/ssh-terminal-spec.md), [Copilot](../06-features/copilot-on-phone-spec.md), [VS Code](../06-features/vscode-on-phone-spec.md)
