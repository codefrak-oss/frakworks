import SwiftUI
import FirstMateKit

/// The Needs sheet (ios/README.md, "What the app does"): the engine's `GET /needs?for=<login>` and the HITLP server's
/// `GET /tasks`, loaded side by side and merged oldest-waiting first. The engine's rows are read-only (a ticket row
/// opens its sheet); a server task has a decision card. A `needs` frame on the engine's `GET /events`, or any event on
/// the server's `GET /events`, reloads it while it is open. An unset or unreachable server is one note line.
struct NeedsView: View {
    /// The row or task to open on (a tapped push), or nil.
    var focus: String?
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var items: [NeedsItem]?
    @State private var error: String?
    @State private var hitlpNote: String?
    @State private var login: String?
    @State private var ticket: TicketLink?
    @State private var settings = false

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List {
                    if let error { Text(error).foregroundStyle(.red) }
                    if let hitlpNote {
                        Button { settings = true } label: { Label(hitlpNote, systemImage: "info.circle") }
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    if let items {
                        if items.isEmpty { Text("Nothing needs you.").foregroundStyle(.secondary) }
                        ForEach(items) { item in
                            Group {
                                switch item {
                                case let .task(task):
                                    DecisionCard(task: task, highlighted: task.id == focus, open: { ticket = $0 }) { await load() }
                                case let .engine(row):
                                    EngineRow(row: row) { ticket = $0 }
                                }
                            }.id(item.actionId)
                        }
                    } else if error == nil { ProgressView() }
                }
                .onChange(of: items) {
                    if let focus, items?.contains(where: { $0.actionId == focus }) == true { withAnimation { proxy.scrollTo(focus, anchor: .top) } }
                }
            }
            .refreshable { await load() }
            .navigationTitle("Needs you").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { settings = true } label: { Image(systemName: "gearshape") }.accessibilityLabel("HITLP server")
                }
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .sheet(item: $ticket) { TicketSheet(link: $0) }
        .sheet(isPresented: $settings) { HitlpSettingsView() }
        .task { await follow() }
        .task(id: model.hitlp) { await load(); await followHitlp() }
    }

    func load() async {
        guard let client = model.client else { return }
        let hitlp = model.hitlpClient
        async let serverTasks = Self.tasks(hitlp)
        var rows: [NeedRow]?
        do {
            if login == nil { login = try await client.whoami() }
            rows = try await client.needs(for: login ?? "")
            error = nil
        } catch { self.error = error.localizedDescription }
        let tasks = await serverTasks
        if let rows { items = NeedsMerge.merge(rows, tasks.list) }
        hitlpNote = hitlp == nil ? "No HITLP server set: tap to set its URL and credential." : tasks.failure.map { "HITLP server: \($0)" }
    }

    /// The server's open tasks, or why there are none: nothing when it is unset.
    static func tasks(_ hitlp: HitlpClient?) async -> (list: [SurfaceTask], failure: String?) {
        guard let hitlp else { return ([], nil) }
        do { return (try await hitlp.tasks().filter(\.isOpen), nil) } catch { return ([], error.localizedDescription) }
    }

    /// Reloads on every engine `needs` frame while the sheet is open; the stream ends with the sheet's task.
    func follow() async {
        guard let client = model.client else { return }
        while !Task.isCancelled {
            do { for try await _ in EventStream.needs(client) { await load() } } catch {}
            try? await Task.sleep(for: .seconds(5))
        }
    }

    /// Reloads on every event of the HITLP server's `GET /events`; restarts when the server's settings change.
    func followHitlp() async {
        guard let hitlp = model.hitlpClient else { return }
        while !Task.isCancelled {
            do { for try await _ in hitlp.events() { await load() } } catch {}
            try? await Task.sleep(for: .seconds(5))
        }
    }
}

/// An engine row, read-only: what is wanted, and the ticket's sheet when it has one.
private struct EngineRow: View {
    let row: NeedRow
    let open: (TicketLink) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(row.kind).font(.caption).padding(.horizontal, 6).background(.quaternary, in: .capsule)
                if let repo = row.repo, let n = row.number { Text("\(repo.split(separator: "/").last ?? "")#\(n)").font(.caption).foregroundStyle(.secondary) }
            }
            if !row.title.isEmpty { Text(row.title).font(.subheadline.bold()) }
            Text(row.question ?? row.wanted).font(.footnote)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            if let link = row.ticket { open(link) }
        }
    }
}
