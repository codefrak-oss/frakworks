import Foundation

/// One line of the Needs sheet: an engine row of `GET /needs`, or an open task of the user's HITLP server.
public enum NeedsItem: Identifiable, Equatable, Sendable {
    case engine(NeedRow)
    case task(SurfaceTask)

    public var id: String {
        switch self {
        case let .engine(row): return "engine:\(row.id)"
        case let .task(task): return "hitlp:\(task.id ?? "")"
        }
    }

    /// What a tapped push's `actionId` matches: the row's action id or the task's id.
    public var actionId: String? {
        switch self {
        case let .engine(row): return row.actionId
        case let .task(task): return task.id
        }
    }

    public var since: Date? {
        switch self {
        case let .engine(row): return row.sinceDate
        case let .task(task): return task.createdAt
        }
    }
}

public enum NeedsMerge {
    /// Engine rows and server tasks together, oldest-waiting first; anything with no time last, in its given order.
    public static func merge(_ rows: [NeedRow], _ tasks: [SurfaceTask]) -> [NeedsItem] {
        oldestFirst(rows.map(NeedsItem.engine) + tasks.map(NeedsItem.task), by: \.since)
    }

    static func oldestFirst<T>(_ items: [T], by date: (T) -> Date?) -> [T] {
        items.enumerated().sorted { a, b in
            switch (date(a.element), date(b.element)) {
            case let (x?, y?): return x == y ? a.offset < b.offset : x < y
            case (_?, nil): return true
            case (nil, _?): return false
            case (nil, nil): return a.offset < b.offset
            }
        }.map(\.element)
    }
}
