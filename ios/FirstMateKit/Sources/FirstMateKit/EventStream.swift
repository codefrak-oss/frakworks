import Foundation

/// `GET /events` streams: a repo's `first-mate` frames of one session, or every `needs` frame (the HITLP server's own
/// `GET /events` is `HitlpClient.events()`).
public enum EventStream {
    /// `GET /events?repo=*` (every event) as its `needs` frames: what refreshes the Needs sheet.
    public static func needs(_ client: EngineClient) -> AsyncThrowingStream<NeedsFrame, Error> {
        events(client, repo: "*") { NeedsFrame.decode($0) }
    }

    public static func frames(_ client: EngineClient, repo: String, sessionId: String) -> AsyncThrowingStream<FirstMateFrame, Error> {
        events(client, repo: repo) { FrameDecoder.decode($0, sessionId: sessionId) }
    }

    static func events<T: Sendable>(_ client: EngineClient, repo: String, decode: @escaping @Sendable (SSEEvent) -> T?) -> AsyncThrowingStream<T, Error> {
        stream(client.session, client.eventsRequest(repo: repo), decode: decode)
    }

    /// An SSE stream of [request] on [session], each event through [decode].
    static func stream<T: Sendable>(_ session: URLSession, _ request: URLRequest, decode: @escaping @Sendable (SSEEvent) -> T?) -> AsyncThrowingStream<T, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let (bytes, response) = try await session.bytes(for: request)
                    let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                    guard status == 200 else { throw EngineError(status: status, message: "the event stream was refused") }
                    var parser = SSEParser()
                    var pending = Data()
                    for try await byte in bytes {
                        pending.append(byte)
                        // Hand the parser whole UTF-8 sequences only: flush at an ASCII byte.
                        guard byte < 0x80 else { continue }
                        let chunk = String(decoding: pending, as: UTF8.self)
                        pending.removeAll(keepingCapacity: true)
                        for event in parser.feed(chunk) {
                            if let frame = decode(event) { continuation.yield(frame) }
                        }
                    }
                    continuation.finish()
                } catch { continuation.finish(throwing: error) }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
