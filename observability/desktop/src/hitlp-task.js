// hitlp-task.js — the pure parts of the HITLP task view (src/renderer/hitlp-task.jsx):
// the headline a task is read by, the command or script an approval would run,
// the judge's odds and the small labels around them. Pure, so node tests load it.

const str = (v) => (typeof v === "string" && v.trim() ? v : null);

/** The command an approval would run, from its payload (`payload.command`, an ops server's `run`), or null. */
function scriptOf(row) {
  const p = row && row.payload && typeof row.payload === "object" ? row.payload : null;
  const text = p && str(p.command);
  if (!text) return null;
  const body = text.replace(/\n+$/, "");
  return {
    text: body,
    lines: body.split("\n").length,
    chars: body.length,
    host: str(p.host),
    user: str(p.user),
    shell: str(p.shell) || "bash",
  };
}

// A command short enough to read as part of the headline: one line, 60 characters at most.
const INLINE_MAX = 60;

/**
 * What the task asks, as one line: an ask's question; for an approval that
 * runs a command, "Run `cmd` on host as user" when the command is short and on
 * one line (`{ text: "Run", code, where }`, drawn in that order), else "Run a
 * 28-line script on host as user"; else the approval's action.
 */
function headlineOf(row) {
  if (!row) return { text: "", code: null };
  if (row.kind !== "approve") return splitQuestion(row.question || row.ask || "");
  const s = scriptOf(row);
  if (!s) return { text: row.action || row.ask || "Approval needed", code: null };
  const where = [s.host ? `on ${s.host}` : null, s.user ? `as ${s.user}` : null].filter(Boolean).join(" ");
  if (s.lines === 1 && s.chars <= INLINE_MAX) return { text: "Run", code: s.text, where: where || null };
  const what = s.lines > 1 ? `a ${s.lines}-line script` : "a command";
  return { text: `Run ${what}${where ? ` ${where}` : ""}`, code: null };
}

// A headline reads in one breath: past this many characters it ends at the first sentence.
const HEADLINE_MAX = 120;

/**
 * A question as `{ text, code: null, rest }`: its first line as the headline
 * and the rest below it; a first line over HEADLINE_MAX ends at its first
 * sentence (". ", "? " or "! ") when that comes sooner, the remainder joining
 * `rest`. `rest` is null when nothing is left.
 */
function splitQuestion(q) {
  const s = String(q).trim();
  const nl = s.indexOf("\n");
  let head = nl < 0 ? s : s.slice(0, nl).trim();
  let rest = nl < 0 ? "" : s.slice(nl + 1).trim();
  if (head.length > HEADLINE_MAX) {
    const m = head.match(/^(.+?[.?!])\s+(.+)$/s);
    if (m && m[1].length <= HEADLINE_MAX * 2) { rest = [m[2], rest].filter(Boolean).join("\n\n"); head = m[1]; }
  }
  return { text: head, code: null, rest: rest || null };
}

const ODDS = ["allow", "escalate", "deny"];

/**
 * The judge's odds over allow, escalate and deny, or null: from the
 * recommendation's `probabilities` when it carries them, else from a reason
 * line like "Jev jev-1.13.0: allow 0.69, escalate 0.31, deny 0.00" (the ops
 * server's, which has no field for them).
 */
function oddsOf(rec) {
  if (!rec || typeof rec !== "object") return null;
  const p = rec.probabilities && typeof rec.probabilities === "object" ? rec.probabilities : null;
  if (p && ODDS.some((k) => typeof p[k] === "number")) {
    return Object.fromEntries(ODDS.map((k) => [k, typeof p[k] === "number" ? p[k] : 0]));
  }
  const reasons = Array.isArray(rec.reasons) ? rec.reasons.filter((r) => typeof r === "string") : [];
  for (const r of reasons) {
    const m = r.match(/allow ([\d.]+), escalate ([\d.]+), deny ([\d.]+)/);
    if (m) return { allow: Number(m[1]), escalate: Number(m[2]), deny: Number(m[3]) };
  }
  return null;
}

/** The verdict the odds lean to: the largest of the three. */
const leanOf = (odds) => (odds ? ODDS.reduce((a, b) => (odds[b] > odds[a] ? b : a), "allow") : null);

/** Two letters for an avatar: "ops-laptop" → "OL", "lundmatt1" → "LU", "smith run 7" → "SR". */
function initialsOf(name) {
  const s = str(typeof name === "string" ? name : name && (name.id || name.login || name.name));
  if (!s) return "?";
  const words = s.split(/[\s\-_./]+/).filter(Boolean);
  return (words.length > 1 ? words[0][0] + words[1][0] : s.slice(0, 2)).toUpperCase();
}

/** "23:32", the deadline (epoch seconds) in local time; null without one. */
function clockOf(deadline) {
  if (deadline == null || !Number.isFinite(Number(deadline))) return null;
  const d = new Date(Number(deadline) * 1000);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

/** How much of the time to the deadline is left, 0..1, from when the task was made (`since`, epoch seconds). */
function leftShare(row, now) {
  if (!row || row.deadline == null || row.since == null) return null;
  const total = Number(row.deadline) - Number(row.since);
  if (!(total > 0)) return null;
  return Math.max(0, Math.min(1, (Number(row.deadline) - now) / total));
}

module.exports = { scriptOf, headlineOf, splitQuestion, HEADLINE_MAX, oddsOf, leanOf, initialsOf, clockOf, leftShare, INLINE_MAX };
