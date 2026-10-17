import Foundation

/// The sessions this phone started, newest first, kept in UserDefaults: the engine has no list call, so the
/// transcript list is the ids the app remembers.
public struct StoredSession: Codable, Identifiable, Equatable, Sendable {
    public let id: String
    public let repo: String
    public let startedAt: Date
    public var title: String
    public init(id: String, repo: String, startedAt: Date = Date(), title: String) { self.id = id; self.repo = repo; self.startedAt = startedAt; self.title = title }
}

public final class SessionStore {
    static let key = "firstMate.sessions"
    let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    public var all: [StoredSession] {
        guard let data = defaults.data(forKey: Self.key) else { return [] }
        return (try? JSONDecoder().decode([StoredSession].self, from: data)) ?? []
    }

    public func add(_ s: StoredSession) { write([s] + all.filter { $0.id != s.id }) }
    public func remove(_ id: String) { write(all.filter { $0.id != id }) }
    public func retitle(_ id: String, _ title: String) { write(all.map { var s = $0; if s.id == id { s.title = title }; return s }) }
    public func clear() { defaults.removeObject(forKey: Self.key) }

    private func write(_ list: [StoredSession]) { defaults.set(try? JSONEncoder().encode(list), forKey: Self.key) }
}
