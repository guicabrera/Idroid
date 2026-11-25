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

# ADR-0006: Obsidian Copilot's agent chat on the phone

> **Summary:** A small Obsidian plugin (`idroid-bridge`) drives Copilot's live agent sessions from a 127.0.0.1 HTTP server; the phone reaches it through the ADR-0005 command tunnel with a helper script, so the phone gets the same chats as the Copilot sidebar, with Copilot's tools and permission prompts.

## Context and problem

The phone's Claude Code mode (ADR-0005) works in the vault but lacks what Copilot adds: its MCP tools (Obsidian CLI, Excalidraw, index search), its instructions, diffs and the chat history shown in the sidebar. The Copilot plugin is desktop-only for agent chat and has no public API for sending messages; its only URI handler (`obsidian://copilot-chat?id=`) opens a saved chat.

## Decision

- **Plugin** `obsidian-bridge/` (installed as `.obsidian/plugins/idroid-bridge`): HTTP server on 127.0.0.1 with a random bearer token, written with the port to `~/.idroid/copilot-bridge.json` (0600), plus the helper `~/.idroid/copilot <METHOD> <PATH> [JSON]`.
- **Endpoints:** `GET /state?session&since&wait` (sessions, messages, pending permissions; long-poll on a change fingerprint checked every 400 ms), `POST /send` (existing chat in place, or a new chat), `POST /permission`, `POST /cancel`, `POST /open`.
- **Copilot internals used** (version 4.0.18): `plugins.copilot.agentSessionManager` → `getSessions`, `getSession`, `getChatUIState(id).sendMessage/getMessages/cancel`, `createSession`, `setActiveSession`, `session.getPendingToolPermissions/resolveToolPermission`. All of it is in `copilot()`, `state()` and `summarize()` in the plugin.
- **Phone:** Copilot screen (from the terminal screen) mirrors one chat, picks among live chats, starts new ones, answers permission prompts with Copilot's own options, stops a turn.

## Consequences

- **Positive:** the real Copilot chat from the phone — tools, instructions, history and diffs land on the Mac exactly as if typed there.
- **Risk:** private API. A Copilot update can rename these members; the bridge then fails with an error on the phone, nothing else breaks. Re-check after Copilot updates.
- **Risk:** same trust as the ADR-0005 terminal: anyone with the phone token can drive Copilot.
- **Availability:** Obsidian's renderer grows with use and can crash after hours (2026-11-25: SIGTRAP after 7.5 h, main process left without a window, bridge port dead). `obsidian-bridge/watchdog.sh` (launchd, every 2 min) restarts Obsidian when the bridge doesn't answer or memory passes a limit while idle; the helper restarts it on demand.
- **Limitations:** needs Obsidian open on the Mac; replies refresh per change, not token by token. Saved chats (`GET /history`, `POST /resume` via `getChatHistoryItems` and `loadChatById`) reopen on the Mac too, as the Navigator does.
