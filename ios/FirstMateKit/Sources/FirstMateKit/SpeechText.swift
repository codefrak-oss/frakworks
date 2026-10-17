import Foundation

/// What of a reply is spoken in voice mode: the reply's markdown made into plain sentences for `AVSpeechSynthesizer`.
/// A `frakworks://ticket/...` link is spoken as "ticket <n>", any other link as its text, a bare URL as "link",
/// a fenced code block as "code omitted"; emphasis, inline code marks, headings, bullets and quotes are dropped.
public enum SpeechText {
    /// The longest chunk handed to the synthesiser at once; a longer sentence is split at word boundaries.
    public static let maxChunk = 300

    /// The reply as chunks to speak, in order; empty when nothing is speakable.
    public static func prepare(_ markdown: String, maxChunk: Int = SpeechText.maxChunk) -> [String] {
        let plain = plainText(markdown)
        var chunks: [String] = []
        for sentence in sentences(plain) { chunks += split(sentence, maxChunk: max(maxChunk, 1)) }
        return chunks
    }

    /// The reply as one plain string, before chunking.
    public static func plainText(_ markdown: String) -> String {
        var lines: [String] = []
        var inFence = false
        for raw in markdown.components(separatedBy: "\n") {
            let trimmed = raw.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("```") || trimmed.hasPrefix("~~~") {
                if !inFence { lines.append("Code omitted.") }
                inFence.toggle()
                continue
            }
            if inFence { continue }
            var line = trimmed
            line = replace(line, #"^#{1,6}\s+"#, "")
            line = replace(line, #"^>\s?"#, "")
            line = replace(line, #"^([-*+]|\d+[.)])\s+(\[[ xX]\]\s+)?"#, "")
            if line.isEmpty || line.range(of: #"^([-*_]\s*){3,}$"#, options: .regularExpression) != nil { lines.append(""); continue }
            line = inline(line)
            // A heading or bullet without its own stop still ends a sentence when spoken.
            if let last = line.last, !".!?:;".contains(last) { line += "." }
            lines.append(line)
        }
        return lines.filter { !$0.isEmpty }.joined(separator: " ")
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }

    static func inline(_ s: String) -> String {
        var line = s
        // Images: their alt text.
        line = replace(line, #"!\[([^\]]*)\]\([^)]*\)"#, "$1")
        // Links: a ticket link by its number, any other by its text.
        line = replaceMatches(line, #"\[([^\]]*)\]\(([^)\s]*)[^)]*\)"#) { groups in
            if let ticket = TicketLink(string: groups[2]) { return "ticket \(ticket.number)" }
            return groups[1]
        }
        // Bare URLs and autolinks.
        line = replaceMatches(line, #"<?(frakworks://ticket/[^\s>)]+|[a-zA-Z][a-zA-Z0-9+.-]*://[^\s>)]+)>?"#) { groups in
            if let ticket = TicketLink(string: groups[1]) { return "ticket \(ticket.number)" }
            return "link"
        }
        // "#872" is read as "ticket 872", not "hash 872".
        line = replace(line, #"(^|[\s(])#(\d+)\b"#, "$1ticket $2")
        line = replace(line, #"`([^`]*)`"#, "$1")
        line = replace(line, #"(\*\*|__)(.+?)\1"#, "$2")
        line = replace(line, #"(^|[^\w*])[*_]([^*_\s][^*_]*)[*_](?=$|[^\w*])"#, "$1$2")
        line = replace(line, #"~~(.+?)~~"#, "$1")
        return line.trimmingCharacters(in: .whitespaces)
    }

    /// Sentences, each with its stop, in order.
    static func sentences(_ text: String) -> [String] {
        var out: [String] = []
        var current = ""
        let chars = Array(text)
        for (i, c) in chars.enumerated() {
            current.append(c)
            if ".!?".contains(c), i + 1 == chars.count || chars[i + 1] == " " {
                let s = current.trimmingCharacters(in: .whitespaces)
                if !s.isEmpty { out.append(s) }
                current = ""
            }
        }
        let rest = current.trimmingCharacters(in: .whitespaces)
        if !rest.isEmpty { out.append(rest) }
        return out
    }

    /// A sentence longer than `maxChunk` split at spaces; a single longer word is cut.
    static func split(_ sentence: String, maxChunk: Int) -> [String] {
        guard sentence.count > maxChunk else { return [sentence] }
        var out: [String] = []
        var current = ""
        for word in sentence.split(separator: " ") {
            var w = String(word)
            while w.count > maxChunk {
                if !current.isEmpty { out.append(current); current = "" }
                out.append(String(w.prefix(maxChunk)))
                w = String(w.dropFirst(maxChunk))
            }
            if current.isEmpty { current = w }
            else if current.count + 1 + w.count <= maxChunk { current += " " + w }
            else { out.append(current); current = w }
        }
        if !current.isEmpty { out.append(current) }
        return out
    }

    private static func replace(_ s: String, _ pattern: String, _ template: String) -> String {
        s.replacingOccurrences(of: pattern, with: template, options: .regularExpression)
    }

    private static func replaceMatches(_ s: String, _ pattern: String, _ transform: ([String]) -> String) -> String {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return s }
        let ns = s as NSString
        var out = ""
        var last = 0
        for m in re.matches(in: s, range: NSRange(location: 0, length: ns.length)) {
            out += ns.substring(with: NSRange(location: last, length: m.range.location - last))
            let groups = (0..<m.numberOfRanges).map { i -> String in
                let r = m.range(at: i)
                return r.location == NSNotFound ? "" : ns.substring(with: r)
            }
            out += transform(groups)
            last = m.range.location + m.range.length
        }
        out += ns.substring(from: last)
        return out
    }
}
