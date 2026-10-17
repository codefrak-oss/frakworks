import SwiftUI
import FirstMateKit

/// A HITLP task's decision card: an ask's options as buttons (or a text field for a free answer), or an approve's
/// action and payload with approve and reject and a reason. Decided on the HITLP server itself (`POST
/// /tasks/{id}/decision`, channel `iphone`); the server's refusal shows on the card. A task whose `context` names a
/// ticket shows `name#n`, which opens that ticket's sheet.
struct DecisionCard: View {
    let task: SurfaceTask
    var highlighted = false
    let open: (TicketLink) -> Void
    /// Reloads the list after a decision.
    let decided: () async -> Void
    @Environment(AppModel.self) private var model
    @State private var answer = ""
    @State private var reason = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(task.kind).font(.caption).padding(.horizontal, 6).background(.quaternary, in: .capsule)
                if let link = task.ticket {
                    Button("\(link.name)#\(link.number)") { open(link) }.font(.caption).buttonStyle(.borderless)
                }
            }
            if let title = task.title, !title.isEmpty { Text(title).font(.subheadline.bold()) }
            if task.kind == "ask" { ask } else { approve }
            if let error { Text(error).font(.footnote).foregroundStyle(.red) }
        }
        .disabled(busy)
        .listRowBackground(highlighted ? Color.accentColor.opacity(0.1) : nil)
    }

    @ViewBuilder private var ask: some View {
        Text(task.question ?? "")
        if task.options.isEmpty {
            TextField("Answer", text: $answer, axis: .vertical).textFieldStyle(.roundedBorder)
            Button("Send") { decide(.answered, answer: .string(answer)) }
                .buttonStyle(.borderedProminent).disabled(answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        } else {
            ForEach(task.options, id: \.self) { o in
                Button(o.label) { decide(.answered, answer: .string(o.value)) }.buttonStyle(.bordered)
            }
        }
    }

    @ViewBuilder private var approve: some View {
        Text(task.action ?? "").font(.body.monospaced())
        if let payload = task.payload {
            ScrollView(.horizontal) {
                Text(payload.pretty).font(.caption.monospaced()).textSelection(.enabled)
            }.padding(6).background(.quaternary, in: .rect(cornerRadius: 6))
        }
        TextField("Reason", text: $reason, axis: .vertical).textFieldStyle(.roundedBorder)
        HStack {
            Button("Approve") { decide(.approve) }.buttonStyle(.borderedProminent)
            Button("Reject", role: .destructive) { decide(.reject) }.buttonStyle(.bordered)
        }
    }

    func decide(_ outcome: DecisionOutcome, answer: JSONValue? = nil) {
        guard let client = model.hitlpClient, let id = task.id else { return }
        busy = true; error = nil
        Task {
            defer { busy = false }
            do {
                try await client.decide(taskId: id, outcome: outcome, answer: answer, reason: reason,
                                        payloadDigest: task.kind == "approve" ? task.payloadDigest : nil)
                await decided()
            } catch { self.error = error.localizedDescription }
        }
    }
}
