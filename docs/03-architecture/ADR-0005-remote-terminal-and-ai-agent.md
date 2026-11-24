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

> **Summary:** The phone runs commands on the Mac through the existing fs tunnel (ADR-0004): named commands the Mac publishes always work; a free terminal with autocomplete works only when the user turns it on, on the Mac. An AI agent (Anthropic, OpenAI or Google, chosen on the phone) proposes commands that run after the user approves them.

## Context and problem

[ADR-0003](./ADR-0003-remote-actions-on-mac.md) kept the phone to an allowlist and left shells to Tailscale SSH. In practice the user wants one app to control the Mac, run git and dev scripts, Claude Code and the Obsidian CLI, with autocomplete and an AI helper — typing into an SSH app on a phone is the poor experience ADR-0003 itself pointed out.

## Decision

- **Transport:** reuse the ADR-0004 tunnel. New ops on `/fs/{device_id}/{op}`: `GET commands` (catalog: named commands + whether the terminal is on), `PUT exec` and `PUT complete` (JSON body the Mac pulls like an upload). The relay flushes `exec` output chunk by chunk; nothing is stored. Offline Mac → 503, as before: commands are never queued.
- **Named commands** (ADR-0003's allowlist, kept): editable on the Mac (Settings → Commands), with presets for Mac control, git, Claude Code and Obsidian. Input from the phone reaches the script only as `$1`.
- **Free terminal:** off by default, a single switch on the Mac. When on, any paired device can run any command as the user and complete commands/paths (bash `compgen`; zsh completion needs a TTY). Runs are non-interactive (`zsh -l -c`, stdin closed, `TERM=dumb`), 15 min timeout, killed if the phone disconnects. The working folder survives between commands (trailer `0x1E {"exit":N,"cwd":"..."}`).
- **Audit:** every run is appended to `~/Library/Logs/Idroid/commands.log` with the device name.
- **AI agent (phone side):** one tool, `run_command`. Provider, model and API key per provider are set on the phone and stored in EncryptedSharedPreferences; requests go straight from the phone to the provider. Each proposed command is shown editable with Run/Skip; "run without asking" is an explicit opt-in. Defaults: `claude-opus-5-5` (with server-side refusal fallback), `gpt-5`, `gemini-2.5-pro`, all editable.

## Consequences

- **Risk accepted:** with the terminal on, a leaked phone token is remote code execution as the user on the Mac — exactly the risk ADR-0003 avoided. Mitigations: off by default, switch on the Mac only, audit log, relay only reachable on the tailnet, unpairing a device revokes its token.
- **Risk:** command output is sent to the chosen AI provider when the agent is used.
- **Trade-off:** no interactive programs (vim, top, prompts); use Tailscale SSH for those.
- Quick settings tile, widget and "open on Mac" share target from ADR-0003 remain future work.

## Status history

| Date | Status | Notes |
|------|--------|-------|
| 2026-11-24 | accepted | Terminal, named commands and AI agent implemented in relay, macOS and Android. |
