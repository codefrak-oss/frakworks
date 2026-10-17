import SwiftUI
import FirstMateKit

/// A ticket, read-only, from `GET /ticket/<owner>/<name>/<n>`.
struct TicketSheet: View {
    let link: TicketLink
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var ticket: FirstMateKit.TicketDetail?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let t = ticket {
                        Text(t.title).font(.title3.bold())
                        HStack {
                            Text(t.state.lowercased()).font(.caption).padding(.horizontal, 6).background(.quaternary, in: .capsule)
                            ForEach(t.labels, id: \.self) { Text($0).font(.caption).foregroundStyle(.secondary) }
                        }
                        Text((try? AttributedString(markdown: t.body, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(t.body))
                            .textSelection(.enabled)
                        if let url = URL(string: t.url) { Link("Open on GitHub", destination: url) }
                    } else if let error { Text(error).foregroundStyle(.red) }
                    else { ProgressView() }
                }.padding()
            }
            .navigationTitle("\(link.name)#\(link.number)").navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Done") { dismiss() } }
        }
        .task {
            guard let client = model.client else { return }
            do { ticket = try await client.ticket(link) } catch { self.error = error.localizedDescription }
        }
    }
}
