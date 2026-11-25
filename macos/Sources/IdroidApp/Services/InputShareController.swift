import AppKit
import ApplicationServices
import Foundation
import Network

/// Shared mouse and keyboard (ADR-0008). Pushing the pointer past the chosen
/// edge of the Mac's screens hands mouse and keyboard to the phone: the cursor
/// freezes at the edge, and moves, clicks, scrolls and keys go over a live
/// relay pipe to the phone's InputControlService. The phone sends "exit" when
/// its pointer goes back out of the same edge; ⌘⎋ also returns at any time.
///
/// Needs Accessibility permission (event tap). Runs on the main thread.
final class InputShareController {

    enum Edge: String, CaseIterable, Identifiable {
        case right, left
        var id: String { rawValue }
        var label: String { self == .right ? "Direita" : "Esquerda" }
    }

    enum Status: Equatable {
        case off, noPermission, connecting, ready, controlling
    }

    /// Called on the main queue.
    var onStatusChange: ((Status) -> Void)?

    private(set) var status: Status = .off {
        didSet { if status != oldValue { onStatusChange?(status) } }
    }

    private var config: PairingConfig?
    private var apiClient: RelayAPIClient?
    private var deviceID: String?
    private var edge: Edge = .right
    private var socket: URLSessionWebSocketTask?
    private var peerReady = false
    private var remote = false
    private var tap: CFMachPort?
    private var tapSource: CFRunLoopSource?
    private var reconnectWork: DispatchWorkItem?
    private var pingTimer: Timer?
    /// Where the cursor sat when control left; it stays frozen there.
    private var frozenAt = CGPoint.zero
    private var edgePush: CGFloat = 0
    /// Pointer speed multiplier set in Settings.
    var speed: Double = 1.0
    private var pendingDX: Double = 0
    private var pendingDY: Double = 0
    private var flushScheduled = false

    static var hasPermission: Bool { AXIsProcessTrusted() }

    static func requestPermission() {
        let key = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
        _ = AXIsProcessTrustedWithOptions([key: true] as CFDictionary)
    }

    func configure(enabled: Bool, deviceID: String?, edge: Edge, config: PairingConfig?, apiClient: RelayAPIClient) {
        let changed = deviceID != self.deviceID || config?.deviceToken != self.config?.deviceToken
        self.edge = edge
        self.apiClient = apiClient
        guard enabled, let deviceID, let config else {
            stop()
            return
        }
        self.config = config
        self.deviceID = deviceID
        guard Self.hasPermission else {
            stop()
            status = .noPermission
            return
        }
        installTap()
        if changed || socket == nil { connect() }
    }

    func stop() {
        returnToMac(atFraction: nil)
        reconnectWork?.cancel()
        pingTimer?.invalidate()
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        closeUDP()
        peerReady = false
        removeTap()
        deviceID = nil
        status = .off
    }

    // MARK: - Pipe

    private func connect() {
        reconnectWork?.cancel()
        socket?.cancel(with: .goingAway, reason: nil)
        peerReady = false
        guard let config, let apiClient, let deviceID else { return }
        status = .connecting
        let task = apiClient.openInputSocket(config, deviceID: deviceID)
        socket = task
        task.resume()
        listen(task)
        pingTimer?.invalidate()
        pingTimer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
            self?.socket?.sendPing { _ in }
        }
    }

    private func listen(_ task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            DispatchQueue.main.async {
                guard let self, task === self.socket else { return }
                switch result {
                case .success(let message):
                    if case .string(let text) = message { self.handle(text) }
                    self.listen(task)
                case .failure:
                    self.dropped()
                }
            }
        }
    }

    private func dropped() {
        returnToMac(atFraction: nil)
        closeUDP()
        socket = nil
        peerReady = false
        guard deviceID != nil else { return }
        status = .connecting
        let work = DispatchWorkItem { [weak self] in self?.connect() }
        reconnectWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: work)
    }

    private func handle(_ text: String) {
        guard let data = text.data(using: .utf8),
              let msg = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = msg["t"] as? String else { return }
        switch type {
        case "hello":
            peerReady = true
            setupUDP(msg)
            if !remote { status = .ready }
        case "exit":
            returnToMac(atFraction: (msg["y"] as? Double).map { CGFloat($0) })
        default: break
        }
    }

    // MARK: - Direct UDP for moves

    /// One connection per address the phone advertised (LAN and Tailscale);
    /// each datagram goes to all, the phone drops duplicates by sequence.
    private var udpConnections: [NWConnection] = []
    private var udpKey = ""
    private var udpSeq: Int64 = 0

    private func setupUDP(_ msg: [String: Any]) {
        closeUDP()
        guard let port = msg["udp"] as? Int, port > 0, let key = msg["key"] as? String,
              let addrs = msg["addrs"] as? [String], let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else { return }
        udpKey = key
        udpSeq = 0
        for addr in addrs {
            let params = NWParameters.udp
            params.serviceClass = .interactiveVideo
            let conn = NWConnection(host: NWEndpoint.Host(addr), port: nwPort, using: params)
            conn.start(queue: .main)
            udpConnections.append(conn)
        }
    }

    private func closeUDP() {
        udpConnections.forEach { $0.cancel() }
        udpConnections = []
    }

    private func sendMove(_ dx: Double, _ dy: Double) {
        guard !udpConnections.isEmpty else {
            send(["t": "move", "dx": dx, "dy": dy])
            return
        }
        udpSeq += 1
        let data = Data(String(format: "%@|%lld|%.2f|%.2f", udpKey, udpSeq, dx, dy).utf8)
        for conn in udpConnections { conn.send(content: data, completion: .idempotent) }
    }

    private func send(_ msg: [String: Any]) {
        guard let socket, let data = try? JSONSerialization.data(withJSONObject: msg),
              let text = String(data: data, encoding: .utf8) else { return }
        socket.send(.string(text)) { _ in }
    }

    // MARK: - Crossing

    /// The display whose `edge` side the point sits on, if no other display continues past it.
    private func edgeDisplay(at p: CGPoint) -> CGRect? {
        var count: UInt32 = 0
        CGGetActiveDisplayList(0, nil, &count)
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(count))
        CGGetActiveDisplayList(count, &ids, &count)
        let rects = ids.map { CGDisplayBounds($0) }
        guard let screen = rects.first(where: { $0.contains(p) || ($0.insetBy(dx: -1, dy: 0)).contains(p) }) else { return nil }
        let beyond = edge == .right ? CGPoint(x: screen.maxX + 2, y: p.y) : CGPoint(x: screen.minX - 3, y: p.y)
        if rects.contains(where: { $0.contains(beyond) }) { return nil }
        let atEdge = edge == .right ? p.x >= screen.maxX - 1 : p.x <= screen.minX
        return atEdge ? screen : nil
    }

    private func enterPhone(at p: CGPoint, screen: CGRect, dx: CGFloat) {
        // Needs a deliberate push, so brushing the edge doesn't jump.
        edgePush += abs(dx)
        guard edgePush > 40 else { return }
        edgePush = 0
        remote = true
        frozenAt = CGPoint(x: edge == .right ? screen.maxX - 1 : screen.minX, y: p.y)
        CGAssociateMouseAndMouseCursorPosition(0)
        CGDisplayHideCursor(CGMainDisplayID())
        send(["t": "enter", "side": edge == .right ? "left" : "right", "y": Double((p.y - screen.minY) / screen.height)])
        status = .controlling
    }

    private func returnToMac(atFraction y: CGFloat?) {
        guard remote else { return }
        remote = false
        if y == nil { send(["t": "leave"]) }
        var target = frozenAt
        if let y, let screen = edgeDisplay(at: frozenAt) {
            target.y = screen.minY + y * screen.height
        }
        target.x += edge == .right ? -8 : 8
        CGWarpMouseCursorPosition(target)
        CGAssociateMouseAndMouseCursorPosition(1)
        CGDisplayShowCursor(CGMainDisplayID())
        status = peerReady ? .ready : .connecting
    }

    // MARK: - Event tap

    private func installTap() {
        guard tap == nil else { return }
        let types: [CGEventType] = [
            .mouseMoved, .leftMouseDragged, .rightMouseDragged, .leftMouseDown, .leftMouseUp,
            .rightMouseDown, .rightMouseUp, .scrollWheel, .keyDown, .keyUp,
        ]
        let mask = types.reduce(CGEventMask(0)) { $0 | (1 << CGEventMask($1.rawValue)) }
        let callback: CGEventTapCallBack = { _, type, event, refcon in
            guard let refcon else { return Unmanaged.passUnretained(event) }
            let me = Unmanaged<InputShareController>.fromOpaque(refcon).takeUnretainedValue()
            return me.handleEvent(type, event)
        }
        guard let port = CGEvent.tapCreate(
            tap: .cgSessionEventTap, place: .headInsertEventTap, options: .defaultTap,
            eventsOfInterest: mask, callback: callback, userInfo: Unmanaged.passUnretained(self).toOpaque()
        ) else {
            status = .noPermission
            return
        }
        tap = port
        tapSource = CFMachPortCreateRunLoopSource(nil, port, 0)
        CFRunLoopAddSource(CFRunLoopGetMain(), tapSource, .commonModes)
        CGEvent.tapEnable(tap: port, enable: true)
    }

    private func removeTap() {
        if let tapSource { CFRunLoopRemoveSource(CFRunLoopGetMain(), tapSource, .commonModes) }
        if let tap { CGEvent.tapEnable(tap: tap, enable: false) }
        tap = nil
        tapSource = nil
    }

    private func handleEvent(_ type: CGEventType, _ event: CGEvent) -> Unmanaged<CGEvent>? {
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let tap { CGEvent.tapEnable(tap: tap, enable: true) }
            return Unmanaged.passUnretained(event)
        }
        let dx = CGFloat(event.getDoubleValueField(.mouseEventDeltaX))
        let dy = CGFloat(event.getDoubleValueField(.mouseEventDeltaY))

        guard remote else {
            if peerReady, type == .mouseMoved, let screen = edgeDisplay(at: event.location),
               (edge == .right ? dx > 0 : dx < 0) {
                enterPhone(at: event.location, screen: screen, dx: dx)
            } else if type == .mouseMoved {
                edgePush = 0
            }
            return Unmanaged.passUnretained(event)
        }

        switch type {
        case .mouseMoved, .leftMouseDragged, .rightMouseDragged:
            queueMove(Double(dx), Double(dy))
        case .leftMouseDown: flushMove(); send(["t": "down"])
        case .leftMouseUp: flushMove(); send(["t": "up"])
        case .rightMouseDown: send(["t": "rclick"])
        case .rightMouseUp: break
        case .scrollWheel:
            let sy = event.getDoubleValueField(.scrollWheelEventPointDeltaAxis1)
            let sx = event.getDoubleValueField(.scrollWheelEventPointDeltaAxis2)
            send(["t": "scroll", "dx": sx, "dy": sy])
        case .keyDown:
            handleKey(event)
        case .keyUp: break
        default:
            return Unmanaged.passUnretained(event)
        }
        return nil // swallowed: the Mac doesn't see it
    }

    /// Mouse deltas from the tap are unaccelerated: apply a curve here so slow
    /// moves stay precise and fast flicks cross the phone, then batch them into
    /// at most one frame every ~8 ms instead of one per hardware event.
    private func queueMove(_ dx: Double, _ dy: Double) {
        let v = (dx * dx + dy * dy).squareRoot()
        let gain = speed * (1 + min(v, 30) / 6)
        pendingDX += dx * gain
        pendingDY += dy * gain
        guard !flushScheduled else { return }
        flushScheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(8)) { [weak self] in
            guard let self else { return }
            self.flushScheduled = false
            guard self.remote, self.pendingDX != 0 || self.pendingDY != 0 else { return }
            self.sendMove(self.pendingDX, self.pendingDY)
            self.pendingDX = 0
            self.pendingDY = 0
        }
    }

    private func flushMove() {
        guard pendingDX != 0 || pendingDY != 0 else { return }
        sendMove(pendingDX, pendingDY)
        pendingDX = 0
        pendingDY = 0
    }

    private func handleKey(_ event: CGEvent) {
        let code = event.getIntegerValueField(.keyboardEventKeycode)
        let flags = event.flags
        let cmd = flags.contains(.maskCommand)
        if cmd && code == 53 { returnToMac(atFraction: nil); return } // ⌘⎋
        if cmd {
            switch code {
            case 0: send(["t": "key", "k": "selectAll"])      // ⌘A
            case 9:                                             // ⌘V: paste the Mac's clipboard
                if let s = NSPasteboard.general.string(forType: .string) { send(["t": "text", "s": s]) }
            case 4: send(["t": "key", "k": "home"])           // ⌘H
            case 48: send(["t": "key", "k": "recents"])       // ⌘Tab
            case 123: send(["t": "key", "k": "lineStart"])
            case 124: send(["t": "key", "k": "lineEnd"])
            default: break
            }
            return
        }
        let named: [Int64: String] = [
            51: "backspace", 117: "delete", 36: "enter", 76: "enter", 48: "tab", 53: "esc",
            123: "left", 124: "right", 125: "down", 126: "up",
        ]
        if let k = named[code] { send(["t": "key", "k": k]); return }
        if let chars = NSEvent(cgEvent: event)?.characters, !chars.isEmpty,
           chars.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }) {
            send(["t": "text", "s": chars])
        }
    }
}
