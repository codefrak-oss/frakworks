import SwiftUI
import FirstMateKit

/// The sessions this phone started on the repo; swipe to delete ends one (`DELETE /first-mate/sessions/{id}`).
struct TranscriptListView: View {
    @Environment(AppModel.self) private var model
    @State private var list: [StoredSession] = []
    @State private var opened: String?
    @State private var error: String?
    @State private var workItemsOpen = false

    var body: some View {
        List {
            if let error { Text(error).foregroundStyle(.red) }
            ForEach(list) { s in
                NavigationLink(value: s.id) {
                    VStack(alignment: .leading) {
                        Text(s.title).lineLimit(1)
                        Text(s.startedAt, style: .relative).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .onDelete { delete($0) }
        }
        .navigationTitle(model.repo ?? "")
        .navigationDestination(for: String.self) { ChatView(sessionId: $0) }
        .navigationDestination(isPresented: Binding(get: { opened != nil }, set: { if !$0 { opened = nil } })) {
            if let opened { ChatView(sessionId: opened) }
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) { Button("New chat", systemImage: "square.and.pencil") { Task { await start() } } }
            ToolbarItem(placement: .topBarLeading) { Button("Repo") { model.repo = nil } }
            ToolbarItem(placement: .topBarTrailing) { Button("Needs you", systemImage: "tray") { model.needsOpen = true } }
            ToolbarItem(placement: .topBarTrailing) { Button("Work items", systemImage: "list.bullet.rectangle") { workItemsOpen = true } }
        }
        .sheet(isPresented: $workItemsOpen) { WorkItemsView() }
        .onAppear { reload(); takeChat() }
        .onChange(of: model.chat) { takeChat() }
    }

    private func reload() { list = model.sessions.all.filter { $0.repo == model.repo } }

    /// A tapped "your chat answered" notification: open that chat.
    private func takeChat() {
        guard let id = model.chat else { return }
        model.chat = nil
        opened = id
    }

    private func start() async {
        guard let client = model.client, let repo = model.repo else { return }
        do {
            let s = try await client.start(repo: repo)
            model.sessions.add(StoredSession(id: s.id, repo: repo, title: "New chat"))
            reload()
            opened = s.id
        } catch { self.error = error.localizedDescription }
    }

    private func delete(_ offsets: IndexSet) {
        let gone = offsets.map { list[$0] }
        list.remove(atOffsets: offsets)
        Task {
            for s in gone {
                do { try await model.client?.end(s.id) }
                catch let e as EngineError where e.status == 404 {} // already gone on the engine
                catch { self.error = error.localizedDescription }
                model.sessions.remove(s.id)
            }
        }
    }
}
