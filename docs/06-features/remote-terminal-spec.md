---
tags:
  - idroid
  - documentation
  - features
created: 2026-11-24
modified: 2026-11-24
status: active
---

# Remote terminal on the Mac

> **Purpose:** What the phone's terminal screen does on a paired Mac: commands, named commands, autocomplete, the AI agent and the Claude Code mode.

## Overview

Each Mac in the device list has a terminal button (>_). It opens a tab strip ([tabs](#tabs)) whose first tab is this screen. Everything runs on the Mac through the relay's fs tunnel ([ADR-0005](../03-architecture/ADR-0005-remote-terminal-and-ai-agent.md), [API](../07-api-reference/remote-commands-api.md)); nothing is queued for an offline Mac.

## Scope

- **Included:** the Terminal and Commands tabs, input modes, AI settings, Claude Code mode, tabs and rotation.
- **Not included:** interactive shells ([SSH terminal](./ssh-terminal-spec.md)), Copilot ([Copilot on the phone](./copilot-on-phone-spec.md)), VS Code ([VS Code on the phone](./vscode-on-phone-spec.md)).

## Terminal tab

- Runs a command with `zsh -l -c` as the user, non-interactive (stdin closed, `TERM=dumb`, pagers off), 15 min timeout. Output streams in while the command runs; the stop button closes the connection and the Mac kills the command.
- The working folder carries over between commands: the Mac reports the shell's final `$PWD`, so `cd` works. It shows under the Mac's name.
- **Autocomplete:** chips above the input, from this tab's history first, then the Mac (commands for the first word, files and folders after it).
- Free commands need **Allow terminal from paired devices** on the Mac (Settings → Commands). It is **on by default**; when off, only named commands run and a banner says so.

## Commands tab

Named commands the Mac publishes, grouped (Mac, Dev, Claude Code, Obsidian, Files). Edited on the Mac in Settings → Commands; presets include lock screen, play/pause, volume, open URL/app, git status/pull, Ask Claude Code, Obsidian CLI. A command that takes input asks for it; the text reaches the script only as `$1`.

## Input modes

The chip left of the input cycles **$ → AI → Claude**:

| Mode | What a message does |
|------|---------------------|
| `$` | Runs as a shell command. |
| AI | Goes to the AI agent (below). |
| Claude | Goes to Claude Code on the Mac (below). |

### AI agent

- One tool, `run_command`. Every proposed command shows as an editable card with **Run** / **Skip**; the agent reads the output and continues (up to 15 steps). **Run commands without asking** is off by default.
- **Settings (gear):** provider (Anthropic, OpenAI, Google), API key, model. After the key is entered, the phone lists the provider's models and the user picks one; nothing is preselected. Key and model are kept per provider, encrypted on the phone; requests go straight to the provider. Command output is sent to it as context.

### Claude Code mode

- Runs `claude -p` on the Mac **in the Obsidian vault** (the vault Obsidian has open), with the Copilot's vault conventions appended to the system prompt. Each reply's session id is resumed on the next message, so it is a conversation.
- **Access** button: Read only, **Edit notes** (default, `acceptEdits`), Everything (`--dangerously-skip-permissions`). Nobody is at the Mac to approve tools, so this is chosen up front.
- **Chats:** lists the vault's Claude Code conversations (including those the Copilot started, titled by Claude Code) to continue one. **New** starts over.
- The **Ask Claude Code** named command switches to this mode.

## Tabs

- **+** opens a new terminal, an SSH session or VS Code; each tab has a close button; closing the last one returns home.
- Hidden tabs stay alive (commands keep running, SSH stays connected): all tabs are stacked full size, the visible one on top, the rest transparent underneath.
- Rotating the phone (or a keyboard/theme change) doesn't recreate the activity, so tabs survive. They don't survive Android killing the app in the background.

## Related documentation

- [ADR-0005: Remote terminal and AI agent](../03-architecture/ADR-0005-remote-terminal-and-ai-agent.md)
- [Remote commands API](../07-api-reference/remote-commands-api.md)
- [Remote access troubleshooting](../08-troubleshooting/remote-access-troubleshooting.md)
