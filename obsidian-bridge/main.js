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

const HELPER_SCRIPT = `#!/bin/zsh
# Calls the Idroid Bridge plugin in Obsidian: copilot <METHOD> <PATH> [JSON body]
info="$HOME/.idroid/copilot-bridge.json"
[[ -r "$info" ]] || { print -u2 "Idroid Bridge isn't running: open Obsidian on this Mac."; exit 3; }
port=$(/usr/bin/plutil -extract port raw -o - "$info")
token=$(/usr/bin/plutil -extract token raw -o - "$info")
data=()
[[ -n "$3" ]] && data=(--data-binary "$3")
exec /usr/bin/curl -sS --max-time 60 -X "$1" -H "Authorization: Bearer $token" -H "Content-Type: application/json" \\
  "\${data[@]}" "http://127.0.0.1:$port$2"
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
      fs.writeFileSync(INFO, JSON.stringify({ port: this.server.address().port, token: this.token }), { mode: 0o600 });
      fs.writeFileSync(HELPER, HELPER_SCRIPT, { mode: 0o755 });
    });
    // Copilot's own listeners differ per object; a cheap fingerprint of what
    // the phone shows is simpler and catches everything (messages, status,
    // permission prompts, new sessions).
    this.registerInterval(window.setInterval(() => this.watch(), 400));
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
        const session = await m.createSession();
        id = session.internalId;
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
      return this.json(res, 200, { chats: await this.history() });
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
    const known = new Set(pane.server.clients.keys());
    const base = new URL(await pane.server.ensure());
    this.tunePhoneWindow(pane.server, known);
    const cli = TAILSCALE_CLI.find((p) => fs.existsSync(p));
    if (!cli) throw new Error("Tailscale CLI not found on this Mac");
    const run = (args) => new Promise((resolve, reject) => {
      require("child_process").execFile(cli, args, { timeout: 20000 }, (err, stdout, stderr) =>
        err ? reject(new Error((stderr || err.message).trim())) : resolve(stdout));
    });
    await run(["serve", "--bg", `--https=${VSCODE_TAILNET_PORT}`, `http://127.0.0.1:${base.port}`]);
    const status = JSON.parse(await run(["status", "--json"]));
    const host = String(status.Self.DNSName || "").replace(/\.$/, "");
    // Opens a folder (the vault unless the phone picked another), like the pane does.
    const vault = this.app.vault.adapter.getBasePath();
    const target = folder && fs.existsSync(folder) ? folder : vault;
    const url = new URL(`https://${host}:${VSCODE_TAILNET_PORT}${base.pathname}/`);
    url.searchParams.set("folder", target);
    return { url: url.toString(), folder: target, vault, home: os.homedir() };
  }

  /**
   * The phone's VS Code window is the first new bridge client after the phone
   * asked for VS Code: give it the phone settings and fold any split groups.
   */
  tunePhoneWindow(server, known) {
    const deadline = Date.now() + 10 * 60000; // room for the first "trust this folder" prompt
    const timer = window.setInterval(() => {
      for (const [id, client] of server.clients) {
        if (known.has(id)) continue;
        known.add(id);
        client.queue.push({ type: "perf", settings: PHONE_VSCODE_SETTINGS });
        for (const cmd of PHONE_VSCODE_COMMANDS) client.queue.push({ type: "command", id: cmd });
        server.flush(client);
        window.clearInterval(timer);
        return;
      }
      if (Date.now() > deadline) window.clearInterval(timer);
    }, 1000);
    this.register(() => window.clearInterval(timer));
  }

  /** Saved agent chats (Copilot's history notes), most recently used first. */
  async history() {
    const items = await this.app.plugins.plugins.copilot.getChatHistoryItems();
    return items
      .filter((i) => i.backendId) // agent chats; Quick Chat history has no backend
      .map((i) => ({ path: i.id, title: i.title || i.id, lastUsed: new Date(i.lastAccessedAt || i.createdAt || 0).getTime(), backend: i.backendId }))
      .sort((a, b) => b.lastUsed - a.lastUsed)
      .slice(0, 100);
  }

  state(m, sessionId) {
    const sessions = m.getSessions().filter((s) => s.getStatus() !== "closed").map((s) => ({
      id: s.internalId,
      title: (s.getLabel && s.getLabel()) || "New chat",
      status: s.getStatus(),
      backend: s.backendId,
      active: s.internalId === m.activeSessionId,
    }));
    const id = sessionId || m.activeSessionId;
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
    return { version: this.version, sessions, session };
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
