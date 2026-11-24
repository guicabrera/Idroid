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
supersedes: ADR-0003 (in part: arbitrary commands)
superseded_by:
---

# ADR-0005: Remote terminal, named commands and AI agent on the Mac

> **Summary:** The phone runs commands on the Mac through the existing fs tunnel (ADR-0004): named commands and a free terminal with autocomplete (on by default, switchable off on the Mac), an interactive SSH terminal over the tailnet whose key the app installs itself, and an AI agent (Anthropic, OpenAI or Google, model picked from the provider's live list) that proposes commands the user approves.

## Context and problem

[ADR-0003](./ADR-0003-remote-actions-on-mac.md) kept the phone to an allowlist and left shells to Tailscale SSH. In practice the user wants one app to control the Mac, run git and dev scripts, Claude Code and the Obsidian CLI, with autocomplete and an AI helper — typing into an SSH app on a phone is the poor experience ADR-0003 itself pointed out.

## Decision

- **Transport:** reuse the ADR-0004 tunnel. New ops on `/fs/{device_id}/{op}`: `GET commands` (catalog: named commands + whether the terminal is on), `PUT exec` and `PUT complete` (JSON body the Mac pulls like an upload). The relay flushes `exec` output chunk by chunk; nothing is stored. Offline Mac → 503, as before: commands are never queued.
- **Named commands** (ADR-0003's allowlist, kept): editable on the Mac (Settings → Commands), with presets for Mac control, git, Claude Code and Obsidian. Input from the phone reaches the script only as `$1`.
- **Free terminal:** on by default (the user's call, 2026-11-24), a single switch on the Mac turns it off. When on, any paired device can run any command as the user and complete commands/paths (bash `compgen`; zsh completion needs a TTY). Runs are non-interactive (`zsh -l -c`, stdin closed, `TERM=dumb`), 15 min timeout, killed if the phone disconnects. The working folder survives between commands (trailer `0x1E {"exit":N,"cwd":"..."}`).
- **Audit:** every run is appended to `~/Library/Logs/Idroid/commands.log` with the device name.
- **Interactive SSH:** for vim, top, prompts. The phone connects straight to the Mac's Remote Login (sshd) at its MagicDNS name over Tailscale with JSch, rendering with Termux's VT emulator. The phone makes an RSA key on first use (EncryptedSharedPreferences) and appends its public key to `~/.ssh/authorized_keys` through `exec`; the catalog tells the phone the host, user and whether Remote Login is on. The Mac's host key is trusted on first use and a changed key is refused. Turning on Remote Login needs admin rights, so the phone can only open that settings page on the Mac.
- **AI agent (phone side):** one tool, `run_command`. Provider, model and API key per provider are set on the phone and stored in EncryptedSharedPreferences; requests go straight from the phone to the provider. Each proposed command is shown editable with Run/Skip; "run without asking" is an explicit opt-in. No model is assumed: once a key is entered the phone lists the provider's models (`GET /v1/models` for Anthropic and OpenAI, filtered to chat models; `models.list` filtered to `generateContent` for Gemini) and the user picks one. Anthropic requests use server-side refusal fallback on the models that accept it.

## Consequences

- **Risk accepted:** a leaked phone token is remote code execution as the user on the Mac (and lets an attacker add an SSH key) — exactly the risk ADR-0003 avoided. Mitigations: switch on the Mac, audit log, relay only reachable on the tailnet, unpairing a device revokes its token; authorized SSH keys are tagged `idroid@<phone model>` so they can be found and removed.
- **Risk:** command output is sent to the chosen AI provider when the agent is used.
- **Trade-off:** the relay terminal stays non-interactive; interactive programs use the SSH screen, which needs Remote Login on and the phone on the tailnet.
- Quick settings tile, widget and "open on Mac" share target from ADR-0003 remain future work.

## Status history

| Date | Status | Notes |
|------|--------|-------|
| 2026-11-24 | accepted | Terminal, named commands and AI agent implemented in relay, macOS and Android. |
| 2026-11-24 | accepted | Terminal on by default; models listed from the provider; in-app SSH terminal. |
