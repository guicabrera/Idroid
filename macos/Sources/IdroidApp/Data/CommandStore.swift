import Foundation

/// A command this Mac lets paired devices run by name (ADR-0005). `command`
/// is zsh; when `takesInput` is set, the text typed on the phone arrives as
/// "$1" — never pasted into the script.
struct CommandAction: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var command: String
    var takesInput: Bool
    var group: String

    enum CodingKeys: String, CodingKey {
        case id, name, command, group
        case takesInput = "takes_input"
    }
}

/// Persists remote-command settings in UserDefaults like the other stores.
/// The free terminal starts off: turning it on is an explicit choice that
/// lets any paired device run anything this user can.
final class CommandStore {

    private let defaults = UserDefaults.standard
    private let actionsKey = "commands.actions"
    private let terminalKey = "commands.terminalEnabled"

    var terminalEnabled: Bool {
        get { defaults.bool(forKey: terminalKey) }
        set { defaults.set(newValue, forKey: terminalKey) }
    }

    func loadActions() -> [CommandAction] {
        guard let data = defaults.data(forKey: actionsKey),
              let actions = try? JSONDecoder().decode([CommandAction].self, from: data) else { return Self.presets }
        return actions
    }

    func saveActions(_ actions: [CommandAction]) {
        defaults.set(try? JSONEncoder().encode(actions), forKey: actionsKey)
    }

    /// Starting set, editable in Settings → Commands.
    static let presets: [CommandAction] = [
        .init(id: "lock", name: "Lock screen", command: "pmset displaysleepnow", takesInput: false, group: "Mac"),
        .init(id: "play-pause", name: "Play / pause music", command: "osascript -e 'tell application \"Music\" to playpause'", takesInput: false, group: "Mac"),
        .init(id: "volume", name: "Set volume (0–100)", command: "osascript -e \"set volume output volume $1\"", takesInput: true, group: "Mac"),
        .init(id: "open-url", name: "Open URL or app", command: "open \"$1\" 2>/dev/null || open -a \"$1\"", takesInput: true, group: "Mac"),
        .init(id: "sleep", name: "Sleep Mac", command: "pmset sleepnow", takesInput: false, group: "Mac"),
        .init(id: "git-status", name: "git status (folder)", command: "git status -sb", takesInput: false, group: "Dev"),
        .init(id: "git-pull", name: "git pull (folder)", command: "git pull --ff-only", takesInput: false, group: "Dev"),
        .init(id: "claude", name: "Ask Claude Code", command: "claude -p \"$1\"", takesInput: true, group: "Claude Code"),
        .init(id: "obsidian", name: "Obsidian CLI", command: "obsidian $1", takesInput: true, group: "Obsidian"),
        .init(id: "ls", name: "List folder", command: "ls -la", takesInput: false, group: "Files"),
    ]
}
