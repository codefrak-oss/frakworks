// friction.jsx — the Friction notes page (Explorer > Analytics > Friction
// notes, #1046, #1098): the engine.friction_note rows the Smiths (as-you-go and
// the retro) and First mate leave, over a window, shown as the groups the
// engine's grouper put them in (engine/README.md, "Friction notes").
// Native React over GET
// /friction/summary, /friction/notes and /friction/groups, through main
// (window.fleet.frictionSummary/frictionNotes/frictionGroups), plus the two
// corrections any login may make, a Pull out and a Merge into… (frictionPullOut,
// frictionMerge; main supplies the login), plus the two-things mark Jev puts on
// a note, the bug the engine files per group asking why, and Not two things
// (frictionNotTwoThings); no Grafana. The window switch, the
// strip and the tables follow the Jev decisions page (jev.jsx) and the Doctor
// page (doctor.jsx) and share their classes; the charts are jev.jsx's SVG
// idiom, coloured only through the palette's CSS variables and currentColor,
// so they read on either background.
import { useCallback, useEffect, useRef, useState } from "react";
import { WINDOWS, DEFAULT_WINDOW, sinceOf } from "./jev.jsx";

// The store's kinds and sources, in the engine's order (FrictionInput.KINDS, FrictionNote.SOURCES).
export const KINDS = ["bug", "design", "docs", "tooling", "other"];
export const SOURCES = [["as-you-go", "as you go"], ["retro", "retro"], ["first-mate", "First mate"]];
const SOURCE_LABELS = Object.fromEntries(SOURCES);

// How many notes the table asks for: the engine's cap.
export const NOTES_LIMIT = 500;

// ---- the pure parts ------------------------------------------------------------

export const DASH = "—";
/** An ISO date-time as UTC to the minute, so every viewer reads the same one; `—` for none. */
export const fmtUtc = (iso) => {
  const t = Date.parse(iso);
  return iso && Number.isFinite(t) ? `${new Date(t).toISOString().replace("T", " ").slice(0, 16)} UTC` : DASH;
};

/** An ISO date-time as UTC, short (`10-17 19:44`), for a table cell: fmtUtc goes in its tooltip; `—` for none. */
export const fmtShort = (iso) => {
  const t = Date.parse(iso);
  return iso && Number.isFinite(t) ? new Date(t).toISOString().replace("T", " ").slice(5, 16) : DASH;
};
/** A repo's name without its owner (`acme/widgets` is `widgets`). */
const repoName = (r) => String(r).split("/").pop();

/** A count map as [key, count] in `known`'s order, then any other key the engine sent. */
const ordered = (counts, known) => {
  const c = counts || {};
  return [...known, ...Object.keys(c).filter((k) => !known.includes(k))].map((k) => [k, c[k] || 0]);
};

/**
 * The strip's numbers from a summary: the total, the window's distinct groups,
 * and the counts by kind and by source, every known one listed. The totals
 * count raw notes; `groups` stands beside them.
 */
export const frictionStats = (summary) => {
  const s = summary || {};
  return {
    total: typeof s.total === "number" ? s.total : null,
    groups: typeof s.groups === "number" ? s.groups : null,
    byKind: ordered(s.by_kind, KINDS),
    bySource: ordered(s.by_source, SOURCES.map(([k]) => k)),
  };
};

/** The per-day bars' data: [day, count] for every UTC day the summary covers, zeros included. */
export const perDayBars = (summary) =>
  ((summary && summary.per_day) || []).map((d) => [String(d.day), Number(d.count) || 0]);

/** The by-kind bars' data: [kind, count], the store's kinds first. */
export const kindBars = (summary) => ordered(summary && summary.by_kind, KINDS);

/**
 * A note as the table shows it: when, repo, the ticket (`{ repo, issue, href }`
 * with a `frakworks://ticket/` link, or null for a First mate note), source,
 * kind, severity and summary, each `—` when unset.
 */
export const noteRow = (n) => ({
  id: n.id,
  at: fmtShort(n.noted_at),
  atFull: fmtUtc(n.noted_at),
  repo: n.repo || DASH,
  ticket: n.issue != null && n.repo ? { repo: n.repo, issue: n.issue, href: `frakworks://ticket/${n.repo}/${n.issue}` } : null,
  source: SOURCE_LABELS[n.source] || n.source || DASH,
  kind: n.kind || DASH,
  severity: n.severity || DASH,
  summary: n.summary || DASH,
});

/**
 * How a note came to be in its group, for the expanded group's joined-by
 * column: `founder` for the note that founded it, `exact` for a repeated key,
 * `jev 0.91` with the call's confidence, `fallback` (no answer for a day),
 * `no jev` (no jev account), `@login` for a user's pull-out, and `grouping…`
 * for a note the grouper has not placed yet.
 */
export const joinedBy = (n) => {
  if (!n || n.group_id == null) return "grouping…";
  switch (n.grouped_by) {
    case "new": return "founder";
    case "exact": return "exact";
    case "jev": return typeof n.jev_confidence === "number" ? `jev ${n.jev_confidence.toFixed(2)}` : "jev";
    case "fallback": return "fallback";
    case "no-jev": return "no jev";
    case "user": return n.grouped_login ? `@${n.grouped_login}` : "user";
    default: return n.grouped_by || DASH;
  }
};

/** Jev's two-things mark on a note, `two things 0.86`, or null when the note is not marked. */
export const twoThingsLabel = (n) => (n && typeof n.two_things === "number" ? `two things ${n.two_things.toFixed(2)}` : null);

/**
 * A group's two-things bug as the row shows it: `{ repo, issue, state, href }`
 * with a `frakworks://ticket/` link once it has a number (`filing…` before the
 * intake refined it), or null without one.
 */
export const bugLink = (g) => {
  const b = g && g.bug;
  if (!b || !b.repo) return null;
  return b.issue != null
    ? { repo: b.repo, issue: b.issue, state: b.state || "open", href: `frakworks://ticket/${b.repo}/${b.issue}`, label: `#${b.issue}${b.state === "closed" ? " (closed)" : ""}` }
    : { repo: b.repo, issue: null, state: b.state || "filing", href: null, label: "filing…" };
};

/**
 * A group's fixed-by link as the row shows it (engine/README.md, "Friction
 * notes", "Fixed by"): `{ repo, issue, state, href, label }`, the label `#n` in
 * the group's only repo and `owner/name#n` otherwise, the state the group's
 * (`fix-filed`, `fixed`, `recurred`); null with no link. A pending candidate
 * fix (an intake that has not filed its ticket yet) is `{ intake, state:
 * "fix-pending", href: null, label: "Fix pending: intake <short id>" }`.
 */
export const fixLink = (g) => {
  const f = g && g.fix;
  if (f && f.intake && f.issue == null)
    return { repo: f.repo || null, issue: null, intake: f.intake, state: "fix-pending", recurredAt: null,
      href: null, label: `Fix pending: intake ${String(f.intake).slice(0, 8)}` };
  if (!f || !f.repo || f.issue == null) return null;
  const own = Array.isArray(g.repos) && g.repos.length === 1 && g.repos[0] === f.repo;
  return { repo: f.repo, issue: f.issue, state: g.state || f.state || "fix-filed", recurredAt: f.recurredAt || null,
    href: `frakworks://ticket/${f.repo}/${f.issue}`, label: `${own ? "" : f.repo}#${f.issue}` };
};

/** Whether a group is fixed and stays so: a recurred group is not. */
export const isFixed = (g) => !!g && g.state === "fixed";

/** The groups the table shows: the fixed ones only when `showFixed`; a recurred group always. */
export const visibleGroups = (groups, showFixed) =>
  (Array.isArray(groups) ? groups : []).filter((g) => showFixed || !isFixed(g));

/** How many groups the "Show fixed (n)" toggle hides. */
export const fixedCount = (groups) => (Array.isArray(groups) ? groups : []).filter(isFixed).length;

/**
 * What the "Fixed by" field means: `#n` (or `n`) in [repo], `owner/name#n`
 * anywhere; `{ fix: { repo, issue } }`, or `{ error }`.
 */
export const parseFix = (text, repo) => {
  const t = String(text || "").trim();
  const m = /^(?:([\w.-]+\/[\w.-]+))?#?(\d+)$/.exec(t);
  if (!m || Number(m[2]) < 1) return { error: `not a ticket: "${t}" (#n or owner/name#n)` };
  const r = m[1] || repo;
  if (!r) return { error: "this group has notes in several repos: write owner/name#n" };
  return { fix: { repo: r, issue: Number(m[2]) } };
};

/** A group's count: `30`, or `9 of 30` when the window or the filters hide members. */
export const countLabel = (g) => {
  const count = Number(g && g.count), total = Number(g && g.total);
  if (!Number.isFinite(count)) return DASH;
  return Number.isFinite(total) && total !== count ? `${count} of ${total}` : String(count);
};

/**
 * A group as the table shows it: the count, the newest and oldest note's time,
 * the majority kind (`mixed` on a tie, as the engine says), the repos (each
 * once, `—` for none), the highest severity and the title.
 */
export const groupRow = (g) => ({
  id: g.id,
  count: countLabel(g),
  newest: fmtShort(g.newest),
  newestFull: fmtUtc(g.newest),
  oldest: fmtShort(g.oldest),
  oldestFull: fmtUtc(g.oldest),
  kind: g.kind || DASH,
  repos: Array.isArray(g.repos) && g.repos.length ? g.repos.map(repoName).join(", ") : DASH,
  reposFull: Array.isArray(g.repos) && g.repos.length ? g.repos.join(", ") : DASH,
  severity: g.severity || DASH,
  title: g.title || DASH,
  bug: bugLink(g),
  fix: fixLink(g),
  state: g.state || "open",
});

const at = (iso) => { const t = Date.parse(iso); return Number.isFinite(t) ? t : 0; };

/**
 * The table's rows, newest activity first: every group ({ kind: "group", group })
 * and every note not yet grouped ({ kind: "note", note }: the notes with a null
 * group_id, each its own row marked grouping…).
 */
export const tableRows = (groups, notes) =>
  (Array.isArray(groups) ? groups : []).map((g) => ({ kind: "group", key: `g${g.id}`, at: at(g.newest), group: g }))
    .concat((Array.isArray(notes) ? notes : []).filter((n) => n.group_id == null).map((n) => ({ kind: "note", key: `n${n.id}`, at: at(n.noted_at), note: n })))
    .sort((a, b) => b.at - a.at || String(a.key).localeCompare(String(b.key)));

/** What the page shows below the strip: "loading", "error", "empty" (zero notes in the window) or "notes". */
export const pageState = (result) => {
  const r = result || { loading: true };
  if (r.error) return "error";
  if (r.loading || !r.summary) return "loading";
  return r.summary.total ? "notes" : "empty";
};

// ---- charts ------------------------------------------------------------------

const W = 520, CH = 70;
const AXIS = { stroke: "currentColor", strokeOpacity: 0.15 };
const BAR = "var(--p-impl)";
const plural = (n, one) => `${n} ${one}${n === 1 ? "" : "s"}`;

/** Notes per UTC day, one bar a day across the window. */
export const FrictionPerDay = ({ days }) => {
  const max = Math.max(0, ...days.map(([, v]) => v));
  if (!days.length || !max) return null;
  const bw = W / days.length, y = (v) => CH - (v / max) * (CH - 12);
  return (
    <svg width={W} height={CH + 14} className="jevChart frictionPerDay">
      <text x={W - 2} y={9} textAnchor="end">{max}</text>
      <line x1="0" x2={W} y1={CH} y2={CH} style={AXIS} />
      {days.map(([d, v], i) => (
        <rect key={d} x={i * bw + 1} width={Math.max(1, bw - 2)} y={y(v)} height={CH - y(v)} style={{ fill: BAR, fillOpacity: 0.85 }}>
          <title>{`${d}: ${plural(v, "note")}`}</title>
        </rect>
      ))}
      <text x={0} y={CH + 11} textAnchor="start">{days[0][0]}</text>
      <text x={W} y={CH + 11} textAnchor="end">{days[days.length - 1][0]}</text>
    </svg>
  );
};

/** Notes by kind as horizontal bars, labelled and counted. */
export const FrictionByKind = ({ rows }) => {
  const max = Math.max(0, ...rows.map(([, v]) => v));
  if (!max) return null;
  const LW = 120, RH = 14, span = W - LW - 30;
  return (
    <svg width={W} height={rows.length * RH + 2} className="jevChart frictionByKind">
      {rows.map(([k, v], i) => (
        <g key={k}>
          <text x={LW - 6} y={i * RH + 10} textAnchor="end">{k}</text>
          <rect x={LW} y={i * RH + 2} height={RH - 4} width={Math.max(v ? 1 : 0, (span * v) / max)} style={{ fill: BAR, fillOpacity: 0.85 }}>
            <title>{`${k}: ${plural(v, "note")}`}</title>
          </rect>
          <text x={LW + (span * v) / max + 4} y={i * RH + 10}>{v}</text>
        </g>
      ))}
    </svg>
  );
};

// ---- the strip, the filters and the table --------------------------------------

/** The top strip: the window switch, the total, the groups beside it, and the counts by kind and by source, all over raw notes but the groups. */
export const FrictionStrip = ({ stats, win, onWindow, onRefresh, refreshing }) => {
  const s = stats || frictionStats(null);
  const num = (v) => (s.total == null ? "–" : v);
  return (
    <div className="jevStrip frictionStrip">
      <div className="jevWindows">
        {WINDOWS.map(([k]) => (
          <button key={k} className={k === win ? "on" : ""} onClick={() => onWindow && onWindow(k)}>{k}</button>
        ))}
        {onRefresh && <button className={"refreshBtn" + (refreshing ? " spinning" : "")} title="Refresh now"
                              disabled={refreshing} onClick={() => onRefresh()}>⟳</button>}
      </div>
      <div className="jevStat"><b>{num(s.total)}</b><span>notes</span></div>
      <div className="jevStat frictionGroupsStat"><b>{s.total == null ? "–" : s.groups == null ? DASH : s.groups}</b><span>groups</span></div>
      {s.byKind.map(([k, v]) => <div key={k} className="jevStat frictionKind"><b>{num(v)}</b><span>{k}</span></div>)}
      <span className="frictionSep" />
      {s.bySource.map(([k, v]) => <div key={k} className="jevStat frictionSource"><b>{num(v)}</b><span>{SOURCE_LABELS[k] || k}</span></div>)}
    </div>
  );
};

/** The table's filters: a kind and a source, each "all" or one of the store's; they narrow it through the endpoint. */
export const FrictionFilters = ({ kind, source, onKind, onSource }) => (
  <div className="frictionFilters">
    <label>kind <select value={kind || ""} onChange={(e) => onKind && onKind(e.target.value || null)}>
      <option value="">all</option>
      {KINDS.map((k) => <option key={k} value={k}>{k}</option>)}
    </select></label>
    <label>source <select value={source || ""} onChange={(e) => onSource && onSource(e.target.value || null)}>
      <option value="">all</option>
      {SOURCES.map(([k, l]) => <option key={k} value={k}>{l}</option>)}
    </select></label>
  </div>
);

const NOTE_COLUMNS = 7;

/**
 * One note as a row, and when open, a second row with its full summary, its
 * evidence and where it came from. Inside a group (`grouped`) the row also
 * shows how the note joined (`joinedBy`) and the two-things mark when Jev put
 * one, and, with `onPullOut`, its Pull out and, on a marked note, Not two things
 * (`onNotTwoThings`).
 */
const FrictionNoteRow = ({ note, open, onToggle, onOpenTicket, grouped, onPullOut, onNotTwoThings, busy }) => {
  const r = noteRow(note);
  const columns = NOTE_COLUMNS + (grouped ? 1 : 0) + (onPullOut ? 1 : 0);
  const two = twoThingsLabel(note);
  return (
    <>
      <tr className={`jevRow frictionRow severity-${note.severity || "unset"}${open ? " open" : ""}`}
          title={open ? "Hide this note" : "Show this note"} onClick={() => onToggle && onToggle(note.id)}>
        <td className="jevWhen" title={r.atFull}>{r.at}</td>
        <td className="jevText" title={note.repo || ""}>{r.repo}</td>
        <td>{r.ticket
          ? <a href={r.ticket.href} title={`${r.ticket.repo}#${r.ticket.issue}`}
               onClick={(e) => { e.stopPropagation(); if (onOpenTicket) { e.preventDefault(); onOpenTicket(r.ticket.repo, r.ticket.issue); } }}>#{r.ticket.issue}</a>
          : DASH}</td>
        <td>{r.source}</td>
        <td>{r.kind}</td>
        <td>{r.severity}</td>
        <td className="jevText" title={note.summary || ""}>{r.summary}{two && grouped && <span className="frictionTwoThings"
          title={note.two_things_call_id ? `Jev marked this note as describing two different things (call ${note.two_things_call_id})` : "Jev marked this note as describing two different things"}>{two}</span>}</td>
        {grouped && <td className="frictionJoined" title={note.jev_call_id ? `jev call ${note.jev_call_id}` : ""}>{joinedBy(note)}</td>}
        {onPullOut && <td className="frictionActions">
          {note.group_id != null && <button className="pvButton frictionPullOut" disabled={!!busy}
                   title="Make a new group holding this note alone"
                   onClick={(e) => { e.stopPropagation(); onPullOut(note.id); }}>Pull out</button>}
          {two && onNotTwoThings && <button className="pvButton frictionNotTwoThings" disabled={!!busy}
                   title="Clear Jev's two-things mark on this note; nothing is filed or closed"
                   onClick={(e) => { e.stopPropagation(); onNotTwoThings(note.id); }}>Not two things</button>}
        </td>}
      </tr>
      {open && (
        <tr className="doctorDetail frictionDetail">
          <td colSpan={columns}>
            <div className="doctorDetailText">{note.summary}</div>
            <div className="jevLabel">evidence</div>
            {note.evidence ? <pre className="jevPre frictionEvidence">{note.evidence}</pre> : <div className="muted">none given</div>}
            <div className="frictionMeta">
              {note.run_id && <span><span className="muted">run</span> {note.run_id}</span>}
              {note.process_instance_id && <span><span className="muted">process instance</span> {note.process_instance_id}</span>}
              {note.login && <span><span className="muted">login</span> {note.login}</span>}
              {note.jev_call_id && <span><span className="muted">jev call</span> {note.jev_call_id}</span>}
            </div>
          </td>
        </tr>
      )}
    </>
  );
};

/**
 * The notes, newest first: noted at, repo, issue, source, kind, severity,
 * summary; with `grouped`, a joined-by column too, and with `onPullOut` a
 * Pull out on each grouped note.
 */
export const FrictionNotesTable = ({ notes, open, onToggle, onOpenTicket, grouped, onPullOut, onNotTwoThings, busy }) => (
  <table className="jevCalls frictionNotes">
    <thead>
      <tr><th className="jevWhen">noted at</th><th>repo</th><th>issue</th><th>source</th><th>kind</th><th>severity</th><th>summary</th>
        {grouped && <th>joined by</th>}{onPullOut && <th>actions</th>}</tr>
    </thead>
    <tbody>
      {notes.map((n) => (
        <FrictionNoteRow key={n.id} note={n} open={!!(open && open[n.id])} onToggle={onToggle} onOpenTicket={onOpenTicket}
                         grouped={grouped} onPullOut={onPullOut} onNotTwoThings={onNotTwoThings} busy={busy} />
      ))}
    </tbody>
  </table>
);

const GROUP_COLUMNS = 9;

/**
 * Merge into…: a `select` over the other groups' titles, a Merge button, then a
 * confirm before anything is sent. Pure: `into` is the chosen id, `confirming`
 * whether the confirm shows.
 */
export const FrictionMergeView = ({ group, others, into, confirming, busy, onInto, onAsk, onConfirm, onCancel }) => {
  const list = Array.isArray(others) ? others : [];
  const target = list.find((g) => String(g.id) === String(into));
  if (!list.length) return <div className="frictionMerge muted">Merge into… no other group in the window</div>;
  return (
    <div className="frictionMerge">
      <label>Merge into…
        <select value={into == null ? "" : String(into)} disabled={!!busy || !!confirming} onChange={(e) => onInto && onInto(e.target.value ? Number(e.target.value) : null)}>
          <option value="">choose a group</option>
          {list.map((g) => <option key={g.id} value={String(g.id)}>{g.title}</option>)}
        </select>
      </label>
      {!confirming && <button className="pvButton frictionMergeAsk" disabled={!target || !!busy} onClick={() => onAsk && onAsk()}>Merge</button>}
      {confirming && target && (
        <span className="frictionConfirm">
          <span>Merge the {plural(group.total, "note")} of “{group.title}” into “{target.title}”? There is no undo but pulling the notes out one by one.</span>
          <button className="pvButton is-go frictionMergeConfirm" disabled={!!busy} onClick={() => onConfirm && onConfirm(target.id)}>Confirm</button>
          <button className="pvButton frictionMergeCancel" disabled={!!busy} onClick={() => onCancel && onCancel()}>Cancel</button>
        </span>
      )}
    </div>
  );
};

/** `FrictionMergeView` with its choice and confirm state; `onMerge(into)` sends. */
const FrictionMerge = ({ group, others, busy, onMerge }) => {
  const [into, setInto] = useState(null);
  const [confirming, setConfirming] = useState(false);
  return <FrictionMergeView group={group} others={others} into={into} confirming={confirming} busy={busy}
                            onInto={(v) => { setInto(v); setConfirming(false); }} onAsk={() => setConfirming(true)}
                            onConfirm={(id) => { setConfirming(false); onMerge && onMerge(group.id, id); }} onCancel={() => setConfirming(false)} />;
};

/**
 * One group as a row (count, newest, oldest, kind, repos, severity, title, and
 * its two-things bug as a ticket link), coloured by its highest severity as a
 * note's row is; open, a second row with its members in the notes table plus
 * joined by, Pull out and Not two things, and Merge into….
 */
const FrictionGroupRow = ({ group, open, onToggle, openNotes, onToggleNote, onOpenTicket, others, onPullOut, onNotTwoThings, onMerge, onFix, busy }) => {
  const r = groupRow(group);
  const recurred = r.state === "recurred";
  return (
    <>
      <tr className={`jevRow frictionGroupRow severity-${group.severity || "unset"}${recurred ? " frictionRecurred" : ""}${open ? " open" : ""}`}
          title={recurred ? `recurred ${fmtUtc(r.fix && r.fix.recurredAt)}: a note arrived after the fix merged` : (open ? "Hide this group's notes" : "Show this group's notes")} onClick={() => onToggle && onToggle(`g${group.id}`)}>
        <td className="frictionCount">{r.count}</td>
        <td className="jevWhen" title={r.newestFull}>{r.newest}</td>
        <td className="jevWhen" title={r.oldestFull}>{r.oldest}</td>
        <td>{r.kind}</td>
        <td className="jevText" title={r.reposFull}>{r.repos}</td>
        <td>{r.severity}</td>
        <td className="jevText frictionTitle" title={group.title || ""}>{r.title}</td>
        <td className="frictionBug">{r.bug
          ? (r.bug.href
            ? <a href={r.bug.href} title={`${r.bug.repo}#${r.bug.issue}: the bug asking why a note in this group carried two things`}
                 onClick={(e) => { e.stopPropagation(); if (onOpenTicket) { e.preventDefault(); onOpenTicket(r.bug.repo, r.bug.issue); } }}>{r.bug.label}</a>
            : <span className="muted" title={`a bug is being filed in ${r.bug.repo}`}>{r.bug.label}</span>)
          : DASH}</td>
        <td className="frictionFix">{r.fix && r.fix.intake
          ? <span className="muted" title={`intake ${r.fix.intake} will file this group's fix`}>{r.fix.label}</span>
          : r.fix
          ? <a href={r.fix.href} title={`${r.fix.repo}#${r.fix.issue} fixes this group: ${r.fix.state}`}
               onClick={(e) => { e.stopPropagation(); if (onOpenTicket) { e.preventDefault(); onOpenTicket(r.fix.repo, r.fix.issue); } }}>{r.fix.label} <span className="muted">({r.fix.state})</span></a>
          : DASH}</td>
      </tr>
      {open && (
        <tr className="doctorDetail frictionGroupDetail">
          <td colSpan={GROUP_COLUMNS}>
            {onFix && <FrictionFixField group={group} busy={busy} onFix={onFix} />}
            <FrictionNotesTable notes={group.members || []} open={openNotes} onToggle={onToggleNote} onOpenTicket={onOpenTicket}
                                grouped onPullOut={onPullOut} onNotTwoThings={onNotTwoThings} busy={busy} />
            {onMerge && <FrictionMerge group={group} others={others} busy={busy} onMerge={onMerge} />}
          </td>
        </tr>
      )}
    </>
  );
};

/** "Fixed by #…" on an open group: Set links the ticket typed, Clear drops the link; `onFix(groupId, fix | null)` sends. */
export const FrictionFixField = ({ group, busy, onFix }) => {
  const [text, setText] = useState("");
  const [error, setError] = useState(null);
  const repo = Array.isArray(group.repos) && group.repos.length === 1 ? group.repos[0] : null;
  const set = () => { const p = parseFix(text, repo); setError(p.error || null); if (p.fix) onFix(group.id, p.fix); };
  return (
    <div className="frictionFixField">
      Fixed by <input className="frictionFixInput" value={text} placeholder={repo ? "#n" : "owner/name#n"} disabled={!!busy}
                      onChange={(e) => setText(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter") set(); }} />
      <button className="pvButton frictionFixSet" disabled={!!busy || !text.trim()} onClick={set}>Set</button>
      {group.fix && <button className="pvButton frictionFixClear" disabled={!!busy} onClick={() => onFix(group.id, null)}>Clear</button>}
      {error && <span className="err">{error}</span>}
    </div>
  );
};

/** A note the grouper has not placed yet, as its own row with no count, marked grouping…; open, the note alone with its detail. */
const FrictionUngroupedRow = ({ note, open, onToggle, openNotes, onToggleNote, onOpenTicket }) => {
  const r = noteRow(note);
  return (
    <>
      <tr className={`jevRow frictionGroupRow frictionUngrouped severity-${note.severity || "unset"}${open ? " open" : ""}`}
          title={open ? "Hide this note" : "Show this note"} onClick={() => onToggle && onToggle(`n${note.id}`)}>
        <td className="frictionCount">{DASH}</td>
        <td className="jevWhen" title={r.atFull}>{r.at}</td>
        <td className="jevWhen" title={r.atFull}>{r.at}</td>
        <td>{r.kind}</td>
        <td className="jevText" title={note.repo || ""}>{note.repo ? repoName(note.repo) : DASH}</td>
        <td>{r.severity}</td>
        <td className="jevText frictionTitle" title={note.summary || ""}>{r.summary} <span className="muted frictionGrouping">({joinedBy(note)})</span></td>
        <td className="frictionBug">{DASH}</td>
        <td className="frictionFix">{DASH}</td>
      </tr>
      {open && (
        <tr className="doctorDetail frictionGroupDetail">
          <td colSpan={GROUP_COLUMNS}>
            <FrictionNotesTable notes={[note]} open={openNotes} onToggle={onToggleNote} onOpenTicket={onOpenTicket} grouped />
          </td>
        </tr>
      )}
    </>
  );
};

/**
 * The groups, newest activity first, each opening to its members, and the notes
 * not yet grouped as their own rows. `open` maps a row key (`g<id>`, `n<id>`) to
 * whether it is open; `openNotes` a note id to whether its detail shows.
 */
export const FrictionGroupsTable = ({ groups, notes, open, onToggle, openNotes, onToggleNote, onOpenTicket, onPullOut, onNotTwoThings, onMerge, onFix, busy }) => {
  const rows = tableRows(groups, notes);
  return (
    <table className="jevCalls frictionGroups">
      <thead>
        <tr><th>count</th><th className="jevWhen">newest</th><th className="jevWhen">oldest</th><th>kind</th><th>repos</th><th>severity</th><th>title</th><th>bug</th><th>fix</th></tr>
      </thead>
      <tbody>
        {rows.map((row) => row.kind === "group"
          ? <FrictionGroupRow key={row.key} group={row.group} open={!!(open && open[row.key])} onToggle={onToggle}
                              openNotes={openNotes} onToggleNote={onToggleNote} onOpenTicket={onOpenTicket}
                              others={(groups || []).filter((g) => g.id !== row.group.id)} onPullOut={onPullOut} onNotTwoThings={onNotTwoThings} onMerge={onMerge} onFix={onFix} busy={busy} />
          : <FrictionUngroupedRow key={row.key} note={row.note} open={!!(open && open[row.key])} onToggle={onToggle}
                                  openNotes={openNotes} onToggleNote={onToggleNote} onOpenTicket={onOpenTicket} />)}
      </tbody>
    </table>
  );
};

// ---- the page ----------------------------------------------------------------

/**
 * The page, drawn from what the loader holds: `result` is { summary } or
 * { error } (one line, nothing else) or { loading }; `notes` is { notes },
 * { error } or { loading }, and `groups` { groups }, { error } or { loading };
 * `open` maps a row key to whether a group (or ungrouped note) is open,
 * `openNotes` a note id to whether its detail shows; `busy` is the correction on
 * its way and `actionError` the engine's word on the last one that failed.
 */
export const FrictionNotesView = ({ result, notes, groups, win = DEFAULT_WINDOW, onWindow, onRefresh, refreshing,
  kind, source, onKind, onSource, open, onToggle, openNotes, onToggleNote, onOpenTicket, onPullOut, onNotTwoThings, onMerge, onFix, busy, actionError, showFixed, onShowFixed }) => {
  const r = result || { loading: true };
  const s = r.summary;
  const state = pageState(r);
  const list = notes && notes.notes;
  const g = groups || { groups: [] };
  const loaded = list && g.groups;
  const shown = loaded ? visibleGroups(g.groups, showFixed) : null;
  const hidden = loaded ? fixedCount(g.groups) : 0;
  const rows = loaded ? tableRows(shown, list) : null;
  return (
    <div className="fleet-root jev friction">
      <div className="head"><h1>Friction notes</h1>
        {s && <span className="meta">{fmtUtc(s.from)} – {fmtUtc(s.to)}</span>}</div>
      <FrictionStrip stats={s ? frictionStats(s) : null} win={win} onWindow={onWindow} onRefresh={onRefresh} refreshing={refreshing} />
      {state === "loading" && <div className="muted">asking the engine for the friction notes…</div>}
      {state === "error" && <div className="err">{String(r.error)}</div>}
      {state === "empty" && <div className="muted">no friction notes in the window</div>}
      {state === "notes" && (
        <>
          <div className="jevLabel">Notes per day</div>
          <FrictionPerDay days={perDayBars(s)} />
          <div className="jevLabel">Notes by kind</div>
          <FrictionByKind rows={kindBars(s)} />
          <div className="jevLabel">Groups</div>
          <FrictionFilters kind={kind} source={source} onKind={onKind} onSource={onSource} />
          {((notes && notes.loading) || g.loading) && <div className="muted">asking the engine for the notes…</div>}
          {notes && notes.error && <div className="err">{String(notes.error)}</div>}
          {g.error && <div className="err">{String(g.error)}</div>}
          {actionError && <div className="err frictionActionError">{String(actionError)}</div>}
          {loaded && hidden > 0 && (
            <label className="frictionShowFixed"><input type="checkbox" checked={!!showFixed} onChange={(e) => onShowFixed && onShowFixed(e.target.checked)} /> Show fixed ({hidden})</label>)}
          {rows && (rows.length
            ? <FrictionGroupsTable groups={shown} notes={list} open={open} onToggle={onToggle} openNotes={openNotes} onToggleNote={onToggleNote}
                                   onOpenTicket={onOpenTicket} onPullOut={onPullOut} onNotTwoThings={onNotTwoThings} onMerge={onMerge} onFix={onFix} busy={busy} />
            : <div className="muted">no notes match the filters</div>)}
        </>
      )}
    </div>
  );
};

const ipcError = (e) => ({ error: `IPC failed: ${e && e.message ? e.message : e}` });

/**
 * Explorer > Analytics > Friction notes: loads the window's summary, notes and
 * groups through `fleet.frictionSummary`/`frictionNotes`/`frictionGroups`, and
 * sends a Pull out or a Merge through `fleet.frictionPullOut`/`frictionMerge`,
 * reloading on success and showing the engine's message on failure; never throws.
 */
export const FrictionNotes = ({ onOpenTicket }) => {
  const [win, setWin] = useState(DEFAULT_WINDOW);
  const [kind, setKind] = useState(null);
  const [source, setSource] = useState(null);
  const [result, setResult] = useState({ loading: true });
  const [notes, setNotes] = useState({ loading: true });
  const [groups, setGroups] = useState({ loading: true });
  const [refreshing, setRefreshing] = useState(false);
  const [open, setOpen] = useState({});
  const [openNotes, setOpenNotes] = useState({});
  const [busy, setBusy] = useState(null); // null | "pull-out" | "not-two-things" | "merge"
  const [actionError, setActionError] = useState(null);
  // The latest load; an answer to an older one (a window or filter switched meanwhile, or the page gone) is dropped.
  const seq = useRef(0);
  const load = useCallback(async () => {
    const mine = ++seq.current;
    const f = typeof window !== "undefined" && window.fleet;
    if (!(f && f.frictionSummary && f.frictionNotes && f.frictionGroups)) {
      setResult({ error: "this build has no Friction notes channel" });
      return;
    }
    const since = sinceOf(win, Date.now());
    setRefreshing(true);
    const ask = (fn) => Promise.resolve().then(fn).catch(ipcError);
    const filter = { since, kind: kind || undefined, source: source || undefined, limit: NOTES_LIMIT };
    const [s, n, g] = await Promise.all([
      ask(() => window.fleet.frictionSummary({ since })),
      ask(() => window.fleet.frictionNotes(filter)),
      ask(() => window.fleet.frictionGroups(filter)),
    ]);
    if (mine !== seq.current) return;
    setRefreshing(false);
    setResult(s && s.summary ? { summary: s.summary } : { error: (s && s.error) || "no response from the main process" });
    setNotes(n && Array.isArray(n.notes) ? { notes: n.notes } : { error: (n && n.error) || "no response from the main process" });
    setGroups(g && Array.isArray(g.groups) ? { groups: g.groups } : { error: (g && g.error) || "no response from the main process" });
  }, [win, kind, source]);
  useEffect(() => {
    setNotes({ loading: true });
    setGroups({ loading: true });
    load();
    return () => { seq.current++; };
  }, [load]);
  const onToggle = useCallback((key) => setOpen((prev) => ({ ...prev, [key]: !prev[key] })), []);
  const onToggleNote = useCallback((id) => setOpenNotes((prev) => ({ ...prev, [id]: !prev[id] })), []);
  // A correction: sent as main's login; the page reloads on success and shows the engine's message on failure.
  const act = useCallback(async (what, send) => {
    setBusy(what); setActionError(null);
    const r = await Promise.resolve().then(send).catch(ipcError);
    if (r && r.error) setActionError(r.error); else await load();
    setBusy(null);
  }, [load]);
  const onPullOut = useCallback((noteId) => act("pull-out", () => window.fleet.frictionPullOut(noteId)), [act]);
  const onNotTwoThings = useCallback((noteId) => act("not-two-things", () => window.fleet.frictionNotTwoThings(noteId)), [act]);
  const onMerge = useCallback((groupId, into) => act("merge", () => window.fleet.frictionMerge(groupId, into)), [act]);
  const onFix = useCallback((groupId, fix) => act("fix", () => window.fleet.frictionFix(groupId, fix)), [act]);
  const [showFixed, setShowFixed] = useState(false);
  return <FrictionNotesView result={result} notes={notes} groups={groups} win={win} onWindow={setWin} onRefresh={load} refreshing={refreshing}
                            kind={kind} source={source} onKind={setKind} onSource={setSource}
                            open={open} onToggle={onToggle} openNotes={openNotes} onToggleNote={onToggleNote} onOpenTicket={onOpenTicket}
                            onPullOut={onPullOut} onNotTwoThings={onNotTwoThings} onMerge={onMerge} onFix={onFix} busy={busy} actionError={actionError}
                            showFixed={showFixed} onShowFixed={setShowFixed} />;
};
