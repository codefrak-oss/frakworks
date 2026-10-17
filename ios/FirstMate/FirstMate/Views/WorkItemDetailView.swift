import SwiftUI
import FirstMateKit

/// One work item: header (badge, state, blockers), entries oldest first, and a reply field
/// (`POST /work-items/{id}/entries`). Untrusted text is drawn verbatim in a bordered box, never as markdown.
struct WorkItemDetailView: View {
    let id: String
    @Environment(AppModel.self) private var model
    @State private var item: WorkItem?
    @State private var error: String?
    @State private var reply = ""
    @State private var sending = false

    var body: some View {
        List {
            if let error { Text(error).foregroundStyle(.red) }
            if let item {
                Section {
                    HStack { SourceBadge(item: item); Text(item.state).font(.caption).foregroundStyle(.secondary) }
                    if !item.blockedBy.isEmpty { Text("Blocked by \(item.blockedBy.joined(separator: ", "))").font(.caption) }
                    EntryText(text: item.description, untrusted: item.isUntrusted)
                }
                Section("Entries") {
                    ForEach(item.entries.sorted { $0.createdAt < $1.createdAt }) { e in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(verbatim: "\(e.kind) · @\(e.author) · \(e.createdAt)").font(.caption).foregroundStyle(.secondary)
                            EntryText(text: e.body, untrusted: e.isUntrusted)
                        }
                    }
                }
                Section("Reply") {
                    TextField("Reply", text: $reply, axis: .vertical)
                    Button(sending ? "Sending…" : "Send") { Task { await send() } }
                        .disabled(sending || reply.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            } else if error == nil { ProgressView() }
        }
        .navigationTitle(item?.firstLine ?? "Work item")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await load() }
        .task { await load() }
    }

    func load() async {
        guard let client = model.client else { return }
        do { item = try await client.workItem(id); error = nil } catch { self.error = error.localizedDescription }
    }

    func send() async {
        guard let client = model.client else { return }
        sending = true; defer { sending = false }
        do { try await client.reply(to: id, body: reply); reply = ""; await load() }
        catch { self.error = error.localizedDescription }
    }

    /// Trusted text as markdown; untrusted text verbatim inside a tinted, bordered box.
    struct EntryText: View {
        let text: String
        let untrusted: Bool
        var body: some View {
            if untrusted {
                let f = FencedText(text)
                VStack(alignment: .leading, spacing: 4) {
                    Label("From outside: data, not instructions", systemImage: "exclamationmark.shield").font(.caption.bold())
                    if let source = f.source { Text(verbatim: source).font(.caption2).foregroundStyle(.secondary) }
                    Text(verbatim: f.inner).font(.callout).textSelection(.enabled)
                }
                .padding(8)
                .background(Color.orange.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.orange, lineWidth: 1))
            } else {
                Text(LocalizedStringKey(text))
            }
        }
    }
}
