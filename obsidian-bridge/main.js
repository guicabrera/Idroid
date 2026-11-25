// Idroid Bridge (ADR-0006): exposes Copilot's agent chat to the Idroid phone
// app. A local HTTP server (127.0.0.1 only, bearer token) drives the live
// AgentSessionManager; the phone reaches it through Idroid's command tunnel by
// running ~/.idroid/copilot, which reads the port and token written here.
// Copilot has no public API for this: everything internal is touched in
// copilot() and summarize(), so an update that breaks it breaks only there.
const { Plugin } = require("obsidian");
const http = require("http");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");

const DIR = path.join(os.homedir(), ".idroid");
const INFO = path.join(DIR, "copilot-bridge.json");
const HELPER = path.join(DIR, "copilot");
const MAX_MESSAGES = 80;
/** Copilot's scope id for chats outside any project. */
const GLOBAL = "__global__";
/** tailnet HTTPS port the phone opens VS Code on (tailscale serve → the vscode-pane proxy). */
const VSCODE_TAILNET_PORT = 8445;
/**
 * Settings for the phone's VS Code window only (VS Code web keeps user settings
 * in each browser, so the Obsidian pane on the Mac is untouched). Claude Code's
 * lockEditorGroups sends every file opened next to a Claude tab into another
 * group, which splits a phone screen; the secondary side bar (Chat) halves it.
 * Applied through VS Code Pane's bridge extension, only where unset.
 */
const PHONE_VSCODE_SETTINGS = {
  "claudeCode.lockEditorGroups": false,
  "workbench.secondarySideBar.defaultVisibility": "hidden",
};
const PHONE_VSCODE_COMMANDS = ["workbench.action.joinAllGroups", "workbench.action.closeAuxiliaryBar"];
const TAILSCALE_CLI = ["/usr/local/bin/tailscale", "/opt/homebrew/bin/tailscale", "/Applications/Tailscale.app/Contents/MacOS/Tailscale"];
const MAX_TOOL_TEXT = 400;
/** Chats the phone created are closed this long after their last change (unless open on the Mac). */
const PHONE_CHAT_IDLE_MS = 30 * 60 * 1000;
/** `tailscale serve` for VS Code is turned off this long after the last phone window left. */
const VSCODE_SERVE_IDLE_MS = 10 * 60 * 1000;
const BUSY = new Set(["starting", "running", "streaming", "awaiting_permission"]);

const WATCHDOG = path.join(DIR, "obsidian-watchdog");

// When Obsidian's renderer crashes the info file stays behind with a dead port
// (and pid); the watchdog restarts Obsidian and the call is made once more.
const HELPER_SCRIPT = `#!/bin/zsh
# Calls the Idroid Bridge plugin in Obsidian: copilot <METHOD> <PATH> [JSON body]
info="$HOME/.idroid/copilot-bridge.json"
watchdog="$HOME/.idroid/obsidian-watchdog"
call() {
  port=$(/usr/bin/plutil -extract port raw -o - "$info")
  token=$(/usr/bin/plutil -extract token raw -o - "$info")
  data=()
  [[ -n "$3" ]] && data=(--data-binary "$3")
  /usr/bin/curl -sS --max-time 60 -X "$1" -H "Authorization: Bearer $token" -H "Content-Type: application/json" \\
    "\${data[@]}" "http://127.0.0.1:$port$2"
}
pid=$([[ -r "$info" ]] && /usr/bin/plutil -extract pid raw -o - "$info" 2>/dev/null)
if [[ ! -r "$info" ]] || { [[ -n "$pid" ]] && ! kill -0 "$pid" 2>/dev/null; }; then
  [[ -x "$watchdog" ]] || { print -u2 "Idroid Bridge isn't running: open Obsidian on this Mac."; exit 3; }
  "$watchdog" ensure || exit 3
fi
call "$@"; rc=$?
if (( rc == 7 )) && [[ -x "$watchdog" ]]; then
  "$watchdog" ensure || exit 3
  call "$@"; rc=$?
fi
exit $rc
`;

module.exports = class IdroidBridge extends Plugin {
  async onload() {
    this.version = 0;
    this.signature = "";
    this.waiters = new Set();
    this.token = crypto.randomBytes(24).toString("hex");
    this.server = http.createServer((req, res) => {
      this.handle(req, res).catch((e) => this.json(res, 500, { error: String((e && e.message) || e) }));
    });
    this.server.listen(0, "127.0.0.1", () => {
      fs.mkdirSync(DIR, { recursive: true, mode: 0o700 });
      // pid: the renderer, so a file left behind by a crash is recognized as stale.
      fs.writeFileSync(INFO, JSON.stringify({ port: this.server.address().port, token: this.token, pid: process.pid }), { mode: 0o600 });
      fs.writeFileSync(HELPER, HELPER_SCRIPT, { mode: 0o755 });
      try {
        const src = path.join(this.app.vault.adapter.getBasePath(), this.manifest.dir, "watchdog.sh");
        fs.writeFileSync(WATCHDOG, fs.readFileSync(src), { mode: 0o755 });
      } catch (_) {}
    });
    // Copilot's own listeners differ per object; a cheap fingerprint of what
    // the phone shows is simpler and catches everything (messages, status,
    // permission prompts, new sessions).
    this.registerInterval(window.setInterval(() => this.watch(), 400));
    this.tuned = new Set();
    this.registerInterval(window.setInterval(() => this.tunePhoneWindows(), 1500));
    // Cleanup: chats the phone started (id → {close, sig, changed}) and the
    // tailnet VS Code endpoint. Serving is assumed on at load (it outlives
    // Obsidian), so it goes off if no phone shows up.
    this.phoneChats = new Map();
    this.vscodeServing = true;
    this.lastPhoneWindow = Date.now();
    this.registerInterval(window.setInterval(() => this.cleanup(), 30000));
  }

  onunload() {
    for (const w of this.waiters) w();
    this.server && this.server.close();
    try {
      if (JSON.parse(fs.readFileSync(INFO, "utf8")).token === this.token) fs.unlinkSync(INFO);
    } catch (_) {}
  }

  copilot() {
    const plugin = this.app.plugins.plugins.copilot;
    const manager = plugin && plugin.agentSessionManager;
    if (!manager) throw new Error("Copilot's agent chat isn't running in Obsidian");
    return manager;
  }

  watch() {
    let sig;
    try {
      const m = this.copilot();
      sig = m.getSessions().map((s) => {
        const ui = m.getChatUIState(s.internalId);
        const msgs = ui ? ui.getMessages() : [];
        const last = msgs[msgs.length - 1];
        const lastLen = last ? (last.message || "").length + (last.parts || []).length : 0;
        return [s.internalId, s.getStatus(), s.getLabel && s.getLabel(), msgs.length, lastLen, s.getPendingToolPermissions().length].join(":");
      }).join("|") + "#" + (m.activeSessionId || "");
    } catch (_) {
      sig = "unavailable";
    }
    if (sig !== this.signature) {
      this.signature = sig;
      this.version++;
      for (const w of this.waiters) w();
      this.waiters.clear();
    }
  }

  async handle(req, res) {
    const ok = req.headers.authorization === "Bearer " + this.token;
    if (!ok) return this.json(res, 401, { error: "unauthorized" });
    const url = new URL(req.url, "http://localhost");
    const body = req.method === "POST" ? await this.body(req) : {};
    const m = this.copilot();

    if (req.method === "GET" && url.pathname === "/state") {
      // Long-poll: answer as soon as something changes after `since`.
      const since = Number(url.searchParams.get("since") || -1);
      const wait = Math.min(Number(url.searchParams.get("wait") || 0), 50) * 1000;
      if (since >= this.version && wait > 0) {
        await new Promise((resolve) => {
          const done = () => { clearTimeout(t); resolve(); };
          const t = setTimeout(() => { this.waiters.delete(done); resolve(); }, wait);
          this.waiters.add(done);
        });
      }
      return this.json(res, 200, this.state(m, url.searchParams.get("session")));
    }
    if (req.method === "POST" && url.pathname === "/send") {
      const text = String(body.text || "").trim();
      if (!text) return this.json(res, 400, { error: "empty message" });
      let id = body.session;
      if (!id) {
        // A new chat from the phone; Copilot shows it on the Mac too. Existing
        // chats are sent to in place, without switching what the Mac shows.
        // In the project the phone picked ("__global__" = no project), without
        // switching the project the Mac is showing.
        const project = this.projectId(body.project);
        const session = await m.createSession(undefined, project === undefined ? m.activeProjectId : project);
        id = session.internalId;
        this.phoneChats.set(id, { close: false, sig: "", changed: Date.now() });
        if (session.ready) await session.ready;
      }
      const ui = m.getChatUIState(id);
      if (!ui) return this.json(res, 404, { error: "conversation not found" });
      ui.sendMessage(text, { notes: [], urls: [], webTabs: [] });
      this.watch();
      return this.json(res, 200, { session: id });
    }
    if (req.method === "POST" && url.pathname === "/permission") {
      const s = m.getSession(body.session);
      if (!s) return this.json(res, 404, { error: "conversation not found" });
      s.resolveToolPermission(body.toolCallId, body.optionId);
      this.watch();
      return this.json(res, 200, {});
    }
    if (req.method === "POST" && url.pathname === "/cancel") {
      const ui = m.getChatUIState(body.session);
      if (ui) await ui.cancel();
      return this.json(res, 200, {});
    }
    if (req.method === "GET" && url.pathname === "/history") {
      return this.json(res, 200, { chats: await this.history(url.searchParams.get("project")) });
    }
    if (req.method === "POST" && url.pathname === "/resume") {
      // Reopens a saved agent chat the way the Copilot Navigator does; Copilot
      // makes it the active chat on the Mac too.
      const chat = (await this.history()).find((c) => c.path === body.path);
      if (!chat) return this.json(res, 404, { error: "saved chat not found" });
      await this.app.plugins.plugins.copilot.loadChatById(chat.path);
      this.watch();
      return this.json(res, 200, { session: m.activeSessionId });
    }
    if (req.method === "POST" && url.pathname === "/vscode") {
      return this.json(res, 200, await this.vscode(body.folder));
    }
    if (req.method === "POST" && url.pathname === "/close") {
      // The phone closed a tab: its chats close once their turn is over.
      const chat = this.phoneChats.get(body.session);
      if (chat) chat.close = true;
      this.cleanup();
      return this.json(res, 200, { closing: !!chat });
    }
    if (req.method === "POST" && url.pathname === "/open") {
      m.setActiveSession(body.session);
      return this.json(res, 200, {});
    }
    return this.json(res, 404, { error: "unknown endpoint" });
  }

  /**
   * VS Code on the phone (ADR-0007): starts the vscode-pane server if needed
   * (its proxy only answers under a secret prefix and injects the connection
   * token) and publishes that proxy on the tailnet over HTTPS with
   * `tailscale serve`, which is idempotent. HTTPS matters: the proxy marks
   * cookies Secure and VS Code web needs a secure context.
   */
  async vscode(folder) {
    const pane = this.app.plugins.plugins["vscode-pane"];
    if (!pane || !pane.server) throw new Error("The VS Code Pane plugin isn't enabled in Obsidian");
    const base = new URL(await pane.server.ensure());
    await this.tailscale(["serve", "--bg", `--https=${VSCODE_TAILNET_PORT}`, `http://127.0.0.1:${base.port}`]);
    this.vscodeServing = true;
    this.lastPhoneWindow = Date.now();
    const status = JSON.parse(await this.tailscale(["status", "--json"]));
    const host = String(status.Self.DNSName || "").replace(/\.$/, "");
    // Opens a folder (the vault unless the phone picked another), like the pane does.
    const vault = this.app.vault.adapter.getBasePath();
    const target = folder && fs.existsSync(folder) ? folder : vault;
    const url = new URL(`https://${host}:${VSCODE_TAILNET_PORT}${base.pathname}/`);
    url.searchParams.set("folder", target);
    return { url: url.toString(), folder: target, vault, home: os.homedir() };
  }

  tailscale(args) {
    const cli = TAILSCALE_CLI.find((p) => fs.existsSync(p));
    if (!cli) return Promise.reject(new Error("Tailscale CLI not found on this Mac"));
    return new Promise((resolve, reject) => {
      require("child_process").execFile(cli, args, { timeout: 20000 }, (err, stdout, stderr) =>
        err ? reject(new Error((stderr || err.message).trim())) : resolve(stdout));
    });
  }

  /**
   * What the phone leaves behind on this Mac:
   * - chats it created: closed when the phone closed their tab (POST /close)
   *   or after PHONE_CHAT_IDLE_MS without changes, never mid-turn, never the
   *   chat open on the Mac (Copilot closes that one itself when it's left);
   * - VS Code: `tailscale serve` off once no phone window has been connected
   *   for VSCODE_SERVE_IDLE_MS (VS Code Pane drops a window 2 min after its
   *   last poll). POST /vscode turns it back on.
   */
  cleanup() {
    const now = Date.now();
    let m = null;
    try { m = this.copilot(); } catch (_) {}
    if (m) {
      for (const [id, chat] of this.phoneChats) {
        const s = m.getSession(id);
        if (!s || s.getStatus() === "closed") { this.phoneChats.delete(id); continue; }
        const ui = m.getChatUIState(id);
        const msgs = ui ? ui.getMessages() : [];
        const sig = [s.getStatus(), msgs.length, msgs.length ? (msgs[msgs.length - 1].message || "").length : 0].join(":");
        if (sig !== chat.sig) { chat.sig = sig; chat.changed = now; }
        if (id === m.activeSessionId || BUSY.has(s.getStatus()) || s.getPendingToolPermissions().length) continue;
        if (chat.close || now - chat.changed > PHONE_CHAT_IDLE_MS) {
          this.phoneChats.delete(id);
          m.closeSession(id).catch((e) => console.warn("Idroid Bridge: closing a phone chat failed", e));
        }
      }
    }
    if (this.vscodeServing && now - this.lastPhoneWindow > VSCODE_SERVE_IDLE_MS) {
      this.vscodeServing = false;
      this.tailscale(["serve", `--https=${VSCODE_TAILNET_PORT}`, "off"]).catch(() => {});
    }
  }

  /**
   * Phone VS Code windows: VS Code Pane's bridge reports each window's host
   * (vscode.env.remoteAuthority); Obsidian's own panes use 127.0.0.1, so any
   * other host is a remote client — the phone over the tailnet. Each gets the
   * phone settings once, whenever it connects.
   */
  tunePhoneWindows() {
    const pane = this.app.plugins.plugins["vscode-pane"];
    const server = pane && pane.server;
    if (!server || !server.clients) return;
    for (const id of this.tuned) if (!server.clients.has(id)) this.tuned.delete(id);
    for (const [id, client] of server.clients) {
      if (!client.authority || /^(127\.0\.0\.1|localhost)(:|$)/.test(client.authority)) continue;
      this.lastPhoneWindow = Date.now();
      if (this.tuned.has(id)) continue;
      this.tuned.add(id);
      client.queue.push({ type: "perf", settings: PHONE_VSCODE_SETTINGS });
      for (const cmd of PHONE_VSCODE_COMMANDS) client.queue.push({ type: "command", id: cmd });
      server.flush(client);
    }
  }

  /** Copilot's projects (not archived), as the Navigator lists them. */
  projects(m) {
    return (m.previousProjectRecords || [])
      .filter((r) => r && r.project && !r.project.archivedAt)
      .map((r) => ({ id: r.project.id, name: r.project.name || r.project.id }))
      .sort((a, b) => a.name.localeCompare(b.name));
  }

  /** A project id from the phone: "__global__", a known project, or undefined. */
  projectId(id) {
    if (!id) return undefined;
    if (id === GLOBAL) return GLOBAL;
    return this.projects(this.copilot()).some((p) => p.id === id) ? id : undefined;
  }

  /**
   * Saved agent chats, most recently used first. Uses the agent manager's own
   * listing (what the Navigator shows): it reads each chat's frontmatter from
   * disk and merges the backends' native sessions, so it doesn't depend on
   * Obsidian's metadata cache being warm (that made the list come and go).
   * `project` = a project id or "__global__" for chats outside projects;
   * none = every chat.
   */
  async history(project) {
    const m = this.copilot();
    const scope = this.projectId(project);
    let items = await m.getChatHistoryItems(scope === GLOBAL ? undefined : scope);
    if (scope === GLOBAL) items = items.filter((i) => !i.projectId || i.projectId === GLOBAL);
    const time = (d) => (d instanceof Date ? d.getTime() : Number(d) || 0);
    return items
      .filter((i) => !i.archivedAt)
      .map((i) => ({
        path: i.id,
        title: i.title || i.id,
        lastUsed: time(i.lastAccessedAt) || time(i.createdAt),
        backend: i.backendId || "",
        project: i.projectId || GLOBAL,
      }))
      .sort((a, b) => b.lastUsed - a.lastUsed)
      .slice(0, 200);
  }

  state(m, sessionId) {
    const sessions = m.getSessions().filter((s) => s.getStatus() !== "closed").map((s) => ({
      id: s.internalId,
      title: (s.getLabel && s.getLabel()) || "New chat",
      status: s.getStatus(),
      backend: s.backendId,
      active: s.internalId === m.activeSessionId,
      project: s.projectId || GLOBAL,
    }));
    // "new" = the phone is composing a new chat: no conversation yet.
    const id = sessionId === "new" ? null : sessionId || m.activeSessionId;
    const s = id ? m.getSession(id) : null;
    const ui = id ? m.getChatUIState(id) : null;
    let session = null;
    if (s && ui) {
      session = {
        id,
        status: s.getStatus(),
        messages: ui.getMessages().filter((x) => x.isVisible !== false).slice(-MAX_MESSAGES).map(summarize),
        permissions: s.getPendingToolPermissions().map((p) => ({
          toolCallId: p.toolCall.toolCallId,
          title: p.toolCall.title || "Tool",
          detail: clip(JSON.stringify(p.toolCall.rawInput || p.toolCall.input || ""), MAX_TOOL_TEXT),
          options: (p.options || []).map((o) => ({ id: o.optionId, name: o.name, kind: o.kind })),
        })),
      };
    }
    return { version: this.version, sessions, session, projects: this.projects(m), project: m.activeProjectId || GLOBAL };
  }

  body(req) {
    return new Promise((resolve, reject) => {
      let data = "";
      req.on("data", (c) => (data += c));
      req.on("end", () => { try { resolve(data ? JSON.parse(data) : {}); } catch (e) { reject(e); } });
      req.on("error", reject);
    });
  }

  json(res, status, obj) {
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(JSON.stringify(obj));
  }
};

function clip(s, n) {
  return s.length > n ? s.slice(0, n) + "…" : s;
}

/** A display message as the phone shows it: text, plus one line per tool call. */
function summarize(m) {
  const parts = m.parts || [];
  const text = parts.length
    ? parts.filter((p) => p.kind === "text").map((p) => p.text).join("\n\n")
    : String(m.message || "");
  const tools = parts.filter((p) => p.kind === "tool_call").map((p) => ({
    title: p.title || p.toolKind || "Tool",
    status: p.status || "",
    detail: clip(p.input ? (p.input.command || p.input.file_path || p.input.pattern || p.input.path || JSON.stringify(p.input)) : "", 160),
  }));
  return { id: m.id, sender: m.sender, text: m.sender === "user" ? String(m.message || "") : text, tools, error: !!m.isErrorMessage };
}
