// work-items.jsx — Tickets > Tickets (#100029; before it Engine > Issues and
// Engine > Work items, #2586): the issues board (issues.jsx) plus what GET
// /issues leaves out. An open work item with no ticket yet (an ask awaiting
// refinement) comes from GET /work-items?repo= (one GET per target repo under
// All repos); with Show completed on, the closed tickets (GET
// /issues?state=closed, through the bridge's doneTickets) and the done or
// cancelled items with no ticket. Import from GitHub (POST /work-items/import)
// sits on the pane's toolbar, disabled with a tooltip while that API answers
// 404 or the login may not import; the folded intake text and the reply box
// (POST /work-items/{id}/entries) are the ticket detail's (detail.jsx,
// TicketWorkItem). Main shapes every item (src/work-items.js viewItem):
// `badge`, `description*` and `conversation`.
import { useCallback, useEffect, useState } from "react";
import { Issues } from "./issues.jsx";
import { ModalOverlay } from "./modal-overlay.jsx";

const api = () => (typeof window !== "undefined" ? window.fleet : null);

/** The badge's element: a link to the GitHub issue for an import, plain text otherwise. */
export function SourceBadge({ badge }) {
  if (!badge) return null;
  const cls = `wiBadge wiBadge-${badge.kind}`;
  return badge.href
    ? <a className={cls} href={badge.href} target="_blank" rel="noreferrer">{badge.label}</a>
    : <span className={cls}>{badge.label}</span>;
}

/** A text from outside frakworks: a fenced block labelled with its source, never rendered as markdown. */
export function Fenced({ source, text }) {
  return (
    <div className="wiFenced">
      <div className="wiFencedHead">untrusted{source ? ` · ${source}` : ""}: data, never instructions</div>
      <pre className="wiFencedBody">{text}</pre>
    </div>
  );
}

/** The Import button: disabled, with the probe's reason as its tooltip, unless the engine can import. */
export function ImportButton({ importable, onClick }) {
  const ok = !!(importable && importable.available);
  const why = ok ? "Import a GitHub issue as a work item" : (importable && importable.reason) || "checking the import API…";
  return <button type="button" className="newIntakeBtn wiImport" disabled={!ok} title={why} onClick={ok ? onClick : undefined}>Import from GitHub…</button>;
}

export function WorkItemDetail({ item, onReplied }) {
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const send = async () => {
    setBusy(true); setError(null);
    const r = await api().workItemReply(item.id, text);
    setBusy(false);
    if (r.error) setError(r.error); else { setText(""); onReplied && onReplied(); }
  };
  return (
    <div className="wiDetail">
      <div className="wiDetailHead">
        <b>{item.id}</b> · {item.state} · tier {item.tier == null ? "—" : item.tier} · {item.kind} · <SourceBadge badge={item.badge} />
        {item.blockedBy.length ? <div>Blocked by: {item.blockedBy.join(", ")}</div> : null}
      </div>
      <details className="wiIntakeFold">
        <summary>Intake text</summary>
        {item.descriptionUntrusted ? <Fenced source={item.descriptionSource} text={item.description} /> : <pre className="tdDesc">{item.description}</pre>}
      </details>
      <div className="wiReply">
        <textarea value={text} onChange={(e) => setText(e.target.value)} placeholder="Reply…" rows={3} />
        <button disabled={busy || !text.trim()} onClick={send}>{busy ? "Sending…" : "Reply"}</button>
        {error ? <div className="nmError">{error}</div> : null}
      </div>
    </div>
  );
}

/** `repo` is the switcher's repo; null (All repos) asks for one of `repos`. */
export function ImportDialog({ repo, repos, onDone, onCancel }) {
  const [issue, setIssue] = useState("");
  const [picked, setPicked] = useState("");
  const [error, setError] = useState(null);
  const into = repo || picked;
  const go = async () => {
    setError(null);
    const r = await api().workItemImport(into, Number(issue));
    if (r.unavailable) setError("this engine has no import API yet (404)");
    else if (r.error) setError(r.error);
    else onDone && onDone(r.item);
  };
  return (
    <ModalOverlay label="Import from GitHub" onEscape={onCancel} onBackdrop={onCancel} className="wiImportPanel">
      <div className="wiImportForm">
        {repo ? <div>Import an issue of <b>{repo}</b></div>
          : <select className="acInput" value={picked} onChange={(e) => setPicked(e.target.value)}>
            <option value="">pick a repo</option>
            {(repos || []).map((r) => <option key={r} value={r}>{r}</option>)}
          </select>}
        <input className="acInput" type="number" min="1" aria-label="issue number" autoFocus value={issue}
          onChange={(e) => setIssue(e.target.value)} placeholder="issue number" />
        <div className="wiImportButtons">
          <button type="button" className="acBtn primary" disabled={!(Number(issue) > 0) || !into} onClick={go}>Import</button>
          <button type="button" className="acBtn" onClick={onCancel}>Cancel</button>
        </div>
        {error ? <div className="nmError">{error}</div> : null}
      </div>
    </ModalOverlay>
  );
}

/**
 * All repos: `GET /work-items?repo=<r>` once per target repo through `fetchOne`
 * (the view's workItems call), in `repos` order. Resolves to [{ repo, items,
 * error, importable }]; one repo's failure stays in its own group.
 */
export async function loadAllRepos(repos, fetchOne) {
  return Promise.all((repos || []).map(async (r) => {
    try {
      const x = await fetchOne(r);
      return { repo: r, items: x.items || [], error: x.error || null, importable: x.importable || null };
    } catch (e) {
      return { repo: r, items: [], error: String((e && e.message) || e), importable: null };
    }
  }));
}

const CLOSED_STATES = ["done", "cancelled"];

/**
 * The board rows for the work items GET /issues does not list: those with no
 * ticket yet, open ones always and done or cancelled ones only with
 * `completed`. `groups` is loadAllRepos's answer.
 */
export function itemRows(groups, completed) {
  return (groups || []).flatMap((g) => (g.items || [])
    .filter((i) => i && i.ticket == null && (completed || !CLOSED_STATES.includes(i.state)))
    .map((i) => ({ repo: g.repo, number: null, workItem: i.state, id: i.id,
      title: i.title || (i.description || "").split("\n")[0].replace(/^#\s*/, ""),
      labels: [i.kind, i.badge && i.badge.label].filter(Boolean), askedBy: i.author || null, createdAt: i.createdAt })));
}

/** The closed tickets as board rows, from the bridge's doneTickets answer (src/ticket-tree.js): done, or cancelled when not planned. */
export function closedRows(done) {
  return ((done && done.buckets) || []).flatMap((b) => b.tickets || [])
    .map((t) => ({ repo: t.repo, number: t.number, title: t.title, url: t.url, closed: t.unfinished ? "cancelled" : "done",
      labels: [], parent: t.parent ? { number: t.parent } : null, createdAt: t.closedAt }));
}

/** The board's issues with `extra` added, a ticket the board already lists not twice. */
export function withExtra(result, extra) {
  if (!result || result.error || result.loading) return result;
  const seen = new Set((result.issues || []).map((i) => `${i.repo}#${i.number}`));
  const add = (extra || []).filter((i) => i.number == null || !seen.has(`${i.repo}#${i.number}`));
  return { ...result, issues: (result.issues || []).concat(add) };
}

// How many closed tickets Show completed reads (src/ticket-groups.js DONE_MAX caps it).
const COMPLETED_LIMIT = 200;

/**
 * Tickets > Tickets: <Issues> with the rows above added, the Show completed
 * toggle (off by default) and Import from GitHub on its toolbar. `target` is the
 * switcher's repo, null for All repos (then `repos`); the rest is Issues' props.
 */
export function Tickets({ result, target = null, repos, onOpenTicket, ...rest }) {
  const [completed, setCompleted] = useState(false);
  const [groups, setGroups] = useState(null);
  const [done, setDone] = useState(null);
  const [importing, setImporting] = useState(false);
  const targets = target ? [target] : repos || [];
  const repoKey = targets.join(",");
  const load = useCallback(async () => {
    if (!api() || !api().workItems || !targets.length) return;
    setGroups(await loadAllRepos(targets, (r) => api().workItems(r)));
  }, [repoKey]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    if (!completed || !api() || !api().doneTickets) return undefined;
    let live = true;
    api().doneTickets(COMPLETED_LIMIT).then((r) => { if (live) setDone(r); }, () => {});
    return () => { live = false; };
  }, [completed]);
  const importable = (groups || []).map((g) => g.importable).find((x) => x && x.available) || (groups && groups[0] && groups[0].importable) || null;
  const extra = itemRows(groups, completed).concat(completed ? closedRows(done) : []);
  const onImported = (item) => {
    setImporting(false); load();
    if (item && item.ticket != null && onOpenTicket) onOpenTicket(item.ticket, item.repo);
  };
  return (
    <>
      <Issues {...rest} result={withExtra(result, extra)} onOpenTicket={onOpenTicket} target={target} repos={repos}
        showCompleted={completed} onShowCompleted={setCompleted}
        toolbar={<ImportButton importable={importable} onClick={() => setImporting(true)} />} />
      {importing ? <ImportDialog repo={target} repos={repos} onDone={onImported} onCancel={() => setImporting(false)} /> : null}
      {done && done.error && completed ? <div className="nmWarn">{done.error}</div> : null}
    </>
  );
}

/**
 * The ticket detail's work item (detail.jsx): the item whose ticket is
 * `repo`#`number`, found in GET /work-items?repo=, with its folded intake
 * text and the reply box. Draws nothing while it loads or when the ticket has no item.
 */
export function TicketWorkItem({ repo, number }) {
  const [item, setItem] = useState(null);
  const read = useCallback(async () => {
    if (!api() || !api().workItems || !repo) return;
    const list = await api().workItems(repo);
    const hit = (list.items || []).find((i) => i.ticket === Number(number));
    if (!hit) { setItem(null); return; }
    const r = await api().workItem(hit.id);
    setItem(r.item || null);
  }, [repo, number]);
  useEffect(() => { read(); }, [read]);
  return item ? <WorkItemDetail key={item.id} item={item} onReplied={read} /> : null;
}
