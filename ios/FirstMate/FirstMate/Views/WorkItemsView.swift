import SwiftUI
import FirstMateKit

/// The work items sheet: `GET /work-items?repo=<repo>`, each row its description's first line, state and source badge.
struct WorkItemsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var items: [WorkItem]?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                if let error { Text(error).foregroundStyle(.red) }
                if let items {
                    if items.isEmpty { Text("No work items.").foregroundStyle(.secondary) }
                    ForEach(items) { item in
                        NavigationLink(value: item.id) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(verbatim: item.firstLine).lineLimit(2)
                                HStack { SourceBadge(item: item); Text(item.state).font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                    }
                } else if error == nil { ProgressView() }
            }
            .navigationTitle("Work items")
            .navigationDestination(for: String.self) { WorkItemDetailView(id: $0) }
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .refreshable { await load() }
            .task { await load() }
        }
    }

    func load() async {
        guard let client = model.client, let repo = model.repo else { return }
        do { items = try await client.workItems(repo: repo); error = nil } catch { self.error = error.localizedDescription }
    }
}

/// The source badge: "My ask", "Imported from GitHub", ...; an untrusted item's badge is orange.
struct SourceBadge: View {
    let item: WorkItem
    var body: some View {
        Text(item.sourceLabel).font(.caption2.bold())
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background((item.isUntrusted ? Color.orange : Color.accentColor).opacity(0.18), in: Capsule())
    }
}
