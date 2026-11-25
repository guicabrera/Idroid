---
tags:
  - idroid
  - documentation
  - features
created: 2026-11-24
modified: 2026-11-24
status: active
---

# SSH terminal

> **Purpose:** The interactive shell on the Mac (vim, top, prompts) and how its setup is automatic.

## Overview

The relay terminal can't run interactive programs. An SSH tab connects straight from the phone to the Mac's Remote Login (sshd) over Tailscale and renders a real VT terminal. Open it from the terminal screen's computer icon or **+ → New SSH session**.

## Setup (automatic)

| Step | What happens |
|------|--------------|
| 1. Catalog | The Mac reports its MagicDNS name, login user and whether port 22 listens. |
| 2. Remote Login off | The tab offers **Open Sharing on the Mac** (turning it on needs an admin, so the user flips System Settings → General → Sharing → Remote Login once) and **Check again**. |
| 3. Key | The phone makes an RSA 3072 key on first use (EncryptedSharedPreferences) and appends its public key, tagged `idroid@<phone model>`, to `~/.ssh/authorized_keys` through the command tunnel. Done once per Mac. |
| 4. Connect | JSch opens a PTY (`xterm-256color`) at the view's size; the Mac's host key is trusted on first use and a changed key is refused. |

## Using it

- Tap the terminal to show the keyboard; no autocorrect or suggestions.
- Extra keys row: sticky **Ctrl** and **Alt**, Esc, Tab, arrows, PgUp/PgDn, Home/End, `| ~ / - * & $ >`, ^C.
- Drag to scroll back (main screen only). A− / A+ change the font; the PTY is resized to match, also on rotation.
- On disconnect the tab shows the reason and **Reconnect**.

## Implementation notes

- Rendering: Termux's `terminal-emulator` and `TerminalRenderer` (JitPack `v0.118.1`) in `SshTerminalView`; Termux's `TerminalView` is tied to a local process. ConnectBot's `termlib` was not used: it needs compileSdk 37 and Kotlin 2.4.
- SSH: `com.github.mwiede:jsch`.

## Related documentation

- [ADR-0005](../03-architecture/ADR-0005-remote-terminal-and-ai-agent.md)
- [Remote terminal](./remote-terminal-spec.md)
- [Remote access troubleshooting](../08-troubleshooting/remote-access-troubleshooting.md)
