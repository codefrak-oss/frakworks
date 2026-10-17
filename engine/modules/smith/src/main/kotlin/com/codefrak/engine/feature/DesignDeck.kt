package com.codefrak.engine.feature

/**
 * A confirm-mode design proposal as a dark, slide-like HTML page (engine/docs/feature.md, "The design deck"): the
 * engine templates it from the stored [DesignResult], so the Smith writes nothing new. One self-contained file with
 * no script and nothing remote, which passes `PitchRules`: the desktop draws it in the sandboxed pitch frame. Every
 * text is HTML-escaped; only the inline `<svg>` mockups of the summary and the questions are carried over, with any
 * script, event handler, `foreignObject` and non-fragment link stripped from them.
 */
object DesignDeck {
    private val opts = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val SVG = Regex("""<svg\b.*?</svg\s*>""", opts)
    private val SVG_STRIP = listOf(
        Regex("""<script\b.*?</script\s*>|<script\b[^>]*/?>""", opts),
        Regex("""<foreignObject\b.*?</foreignObject\s*>|<foreignObject\b[^>]*/?>""", opts),
        Regex("""\son[a-z]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)""", opts),
        Regex("""\s(?:xlink:)?href\s*=\s*(?:"(?!#)[^"]*"|'(?!#)[^']*'|(?!['"#])[^\s>]+)""", opts),
    )
    private val CODE = Regex("`([^`\n]+)`")

    fun render(result: DesignResult, repo: String, issue: Long, title: String? = null): String {
        val plan = result.plan
        val heading = title?.takeIf { it.isNotBlank() } ?: "$repo#$issue"
        val questions = result.important().ifEmpty { result.questions }
        val slides = buildList {
            add(slide("title", "Design proposal", "<h1>${esc(heading)}</h1><p class=\"meta\">${esc("$repo#$issue")} · confirm mode · " +
                "${questions.size} question${if (questions.size == 1) "" else "s"} for you</p>" + rich(plan?.summary.orEmpty())))
            plan?.files?.takeIf { it.isNotEmpty() }?.let { add(slide("files", "Files", list(it, code = true))) }
            plan?.stages?.takeIf { it.isNotEmpty() }?.let { stages ->
                add(slide("stages", "Stages", "<ol class=\"stages\">" + stages.joinToString("") {
                    "<li><strong>${inline(it.title)}</strong>${if (it.detail.isBlank()) "" else "<span>${inline(it.detail)}</span>"}</li>"
                } + "</ol>"))
            }
            plan?.tests?.takeIf { it.isNotEmpty() }?.let { add(slide("tests", "Tests", list(it))) }
            plan?.acceptance?.takeIf { it.isNotEmpty() }?.let { add(slide("acceptance", "Acceptance", list(it))) }
            questions.forEachIndexed { i, q -> add(question(i + 1, questions.size, q)) }
            if (result.outcome == "ask" && !result.question.isNullOrBlank()) add(slide("question", "Question", rich(result.question)))
        }
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="dark"><title>${esc(heading)} · design proposal</title>
<style>$CSS</style></head>
<body><main class="deck">
${slides.joinToString("\n")}
<footer>Answer on the decision card or in the ticket; this page is a view of the plan, not the answer path.</footer>
</main></body></html>
"""
    }

    private fun question(n: Int, of: Int, q: PlanQuestion): String {
        val pct = (q.confidence.coerceIn(0.0, 1.0) * 100).toInt()
        val impact = q.impact.takeIf { it in DesignResult.IMPACTS } ?: "medium"
        val options = q.options?.takeIf { it.isNotEmpty() }?.let { opts ->
            "<h3>Options</h3><ul class=\"options\">" + opts.joinToString("") { "<li><code>${esc(it.id)}</code> ${inline(it.label)}</li>" } + "</ul>"
        }.orEmpty()
        return slide("question", "Question $n of $of", rich(q.question) +
            "<div class=\"facts\"><span class=\"badge impact-$impact\">impact ${esc(impact)}</span>" +
            "<span class=\"meter\" title=\"confidence $pct%\"><span style=\"width:$pct%\"></span></span><span class=\"pct\">confidence $pct%</span></div>" +
            (q.assumption?.takeIf { it.isNotBlank() }?.let { "<div class=\"assume\"><h3>Assumed without an answer</h3>${rich(it)}</div>" }.orEmpty()) +
            options)
    }

    private fun slide(kind: String, label: String, body: String) =
        "<section class=\"slide $kind\"><p class=\"label\">${esc(label)}</p>$body</section>"

    private fun list(items: List<String>, code: Boolean = false) =
        "<ul>" + items.joinToString("") { "<li>${if (code) "<code>${esc(it)}</code>" else inline(it)}</li>" } + "</ul>"

    /** Paragraphs of escaped text, with each inline `<svg>` kept as a figure. */
    internal fun rich(text: String): String = buildString {
        var at = 0
        SVG.findAll(text).forEach { m ->
            append(paragraphs(text.substring(at, m.range.first)))
            append("<figure class=\"mock\">").append(svg(m.value)).append("</figure>")
            at = m.range.last + 1
        }
        append(paragraphs(text.substring(at)))
    }

    internal fun svg(markup: String): String = SVG_STRIP.fold(markup) { s, rule -> rule.replace(s, "") }

    private fun paragraphs(text: String) = text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString("") { "<p>${inline(it).replace("\n", "<br>")}</p>" }

    private fun inline(text: String) = CODE.replace(esc(text)) { "<code>${it.groupValues[1]}</code>" }

    fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private val CSS = """
:root{color-scheme:dark;--bg:#0d1017;--card:#161b26;--line:#262d3d;--fg:#e6e8ee;--dim:#9aa3b5;--accent:#6ea8fe;--warn:#f0b35a;--bad:#ef6b73;--ok:#4cc38a}
@media (prefers-color-scheme: dark){:root{--bg:#0d1017}}
*{box-sizing:border-box}html,body{margin:0;background:var(--bg);color:var(--fg)}
body{font:16px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
.deck{max-width:980px;margin:0 auto;padding:32px 24px 48px;display:flex;flex-direction:column;gap:28px}
.slide{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:36px 44px;min-height:320px;box-shadow:0 10px 30px rgba(0,0,0,.35)}
.slide.title{background:linear-gradient(135deg,#1a2133,#121722);min-height:420px}
.label{margin:0 0 12px;color:var(--accent);font-size:12px;letter-spacing:.14em;text-transform:uppercase;font-weight:600}
h1{margin:0 0 6px;font-size:34px;line-height:1.2}h3{margin:20px 0 6px;font-size:13px;color:var(--dim);text-transform:uppercase;letter-spacing:.08em}
.meta{color:var(--dim);margin:0 0 20px}p{margin:0 0 12px}
ul,ol{margin:0;padding-left:22px}li{margin:6px 0}
code{font:13px ui-monospace,SFMono-Regular,Menlo,monospace;background:#0f131c;border:1px solid var(--line);border-radius:5px;padding:1px 6px}
.stages{counter-reset:s;list-style:none;padding:0}.stages li{counter-increment:s;padding:10px 0 10px 44px;position:relative;border-bottom:1px solid var(--line)}
.stages li::before{content:counter(s);position:absolute;left:0;top:10px;width:28px;height:28px;border-radius:50%;background:var(--accent);color:#0d1017;font-weight:700;text-align:center;line-height:28px}
.stages span{display:block;color:var(--dim)}
.slide.question>p:first-of-type{font-size:20px}
.facts{display:flex;align-items:center;gap:14px;margin:18px 0}
.badge{border-radius:999px;padding:2px 12px;font-size:12px;font-weight:600;border:1px solid}
.impact-low{color:var(--ok)}.impact-medium{color:var(--warn)}.impact-high{color:var(--bad)}
.meter{flex:0 0 160px;height:8px;border-radius:4px;background:#0f131c;border:1px solid var(--line);overflow:hidden}.meter span{display:block;height:100%;background:var(--accent)}
.pct{color:var(--dim);font-size:13px}
.assume{border-left:3px solid var(--accent);padding:4px 0 4px 16px;margin-top:8px}
figure.mock{margin:18px 0;overflow:auto}figure.mock svg{max-width:100%;height:auto}
footer{color:var(--dim);font-size:13px;text-align:center}
"""
}
