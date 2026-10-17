import Foundation

/// One server-sent event: its `event:` name ("message" when none), joined `data:` lines and last `id:`.
public struct SSEEvent: Equatable, Sendable {
    public let event: String
    public let data: String
    public let id: String?
    public init(event: String, data: String, id: String? = nil) { self.event = event; self.data = data; self.id = id }
}

/// An incremental text/event-stream parser (WHATWG "server-sent events"): feed it chunks as they arrive, cut anywhere,
/// and it answers the events each completes. Handles LF, CR and CRLF line ends (a CRLF split across chunks too),
/// multi-line `data:`, `:` comments (the engine's `: ping`), and fields without a value. A block with no data is dropped.
public struct SSEParser: Sendable {
    private var buffer = ""
    private var pendingCR = false
    private var event = ""
    private var data: [String] = []
    private var hasData = false
    private var lastId: String?

    public init() {}

    public mutating func feed(_ chunk: String) -> [SSEEvent] {
        var out: [SSEEvent] = []
        for ch in chunk.unicodeScalars {
            if pendingCR {
                pendingCR = false
                if ch == "\n" { continue }
            }
            if ch == "\r" { pendingCR = true; line(&out); continue }
            if ch == "\n" { line(&out); continue }
            buffer.unicodeScalars.append(ch)
        }
        return out
    }

    private mutating func line(_ out: inout [SSEEvent]) {
        let l = buffer
        buffer = ""
        if l.isEmpty { dispatch(&out); return }
        if l.hasPrefix(":") { return }
        let field: Substring, value: Substring
        if let colon = l.firstIndex(of: ":") {
            field = l[..<colon]
            var v = l[l.index(after: colon)...]
            if v.hasPrefix(" ") { v = v.dropFirst() }
            value = v
        } else { field = Substring(l); value = "" }
        switch field {
        case "event": event = String(value)
        case "data": data.append(String(value)); hasData = true
        case "id": if !value.contains("\0") { lastId = String(value) }
        default: break
        }
    }

    private mutating func dispatch(_ out: inout [SSEEvent]) {
        defer { event = ""; data = []; hasData = false }
        guard hasData else { return }
        out.append(SSEEvent(event: event.isEmpty ? "message" : event, data: data.joined(separator: "\n"), id: lastId))
    }
}
