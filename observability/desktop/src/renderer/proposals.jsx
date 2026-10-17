// proposals.jsx — the explorer's Proposals branch and the proposal view (#660).
// The rows are src/proposal-list.js toProposalRows' merge of the engine's
// proposal docs (`GET /proposals`), the board's proposal tickets and the
// `decision` Needs; this file only draws them.
//
// The view is the Pitch view's parts (pitch.jsx) with the proposal's facts
// above them: its status and where that comes from, the PR, the build ticket
// and the decision log. While a `decision` Need waits, the decision controls
// mount the HITLP task it waits on, answered in place as the Pitch view's are
// (hitlp-task.jsx TicketTask, found by its ticket). The pitch stays in pitch.jsx's sandboxed
// srcdoc frame.

import { useState, useEffect } from "react";
import { ticketId } from "./repo-switcher.jsx";
import { PitchBody, DecisionControls, FoldArrow, loadPitch } from "./pitch.jsx";
import { groupByRepo } from "../proposal-list.js";
import { useRepoName } from "./repo-name-form.jsx";

// The Proposals list's own timer: each repo's default branch on GitHub, which
// the engine does not observe (a doc edited on `main` by hand is announced by
// no one). What the engine does know arrives on its events: the list refetches
// on `flow` `ended` of a proposal and on `ticket` `pr` (index.jsx).
export const PROPOSALS_REFRESH_MS = 5 * 60 * 1000;

const pad = (depth) => 8 + depth * 14;
const same = (a, b) => a && b && a.kind === b.kind && String(a.id ?? "") === String(b.id ?? "");

/** A status as a chip class: `awaiting decision` -> `st-awaiting-decision`. */
export const statusClass = (status) => "st-" + String(status || "unknown").replace(/[^a-z]+/gi, "-").toLowerCase();

const Chip = ({ status }) => <span className={"exStatus " + statusClass(status)}>{status}</span>;

const ProposalRow = ({ r, depth, showRepo, sel, onSelect }) => {
  const item = { kind: "proposal", id: r.id };
  return (
    <div className={"exRow" + (same(sel, item) ? " on" : "")} style={{ paddingLeft: pad(depth) }}
         title={`${r.title} (${r.status})`} onClick={() => onSelect(item)}>
      <span className="exLabel">
        {r.number != null ? <span className="exNum">{showRepo ? r.repo : ""}#{r.number}</span>
          : showRepo ? <span className="exNum">{r.repo}</span> : null} {r.title}
      </span>
      <Chip status={r.status} />
    </div>
  );
};

/**
 * The Proposals branch. `proposals` is `{ rows, error, failures }`, null
 * while the first read is out; `allRepos` draws a sub-branch per repo, in the
 * order of `repos`. Closed unless the page restored is a proposal.
 */
export const ProposalsBranch = ({ proposals, allRepos, repos, open, toggle, sel, onSelect }) => {
  const repoName = useRepoName(repos);
  const rows = (proposals && proposals.rows) || [];
  const isOpen = open.proposals ?? (sel && sel.kind === "proposal");
  const rKey = (repo) => `p:${repo}`;
  const err = (depth) => (
    <>
      {proposals && proposals.error && <div className="exErr" style={{ paddingLeft: pad(depth) }}>{proposals.error}</div>}
      {((proposals && proposals.failures) || []).map((f, i) => (
        <div key={i} className="exErr" style={{ paddingLeft: pad(depth) }}>{f.repo ? `${f.repo}: ` : ""}{f.error}</div>
      ))}
    </>
  );
  return (
    <Branch label="Proposals" count={proposals && !proposals.error ? rows.length : null}
            open={!!isOpen} onToggle={() => toggle("proposals", !!(sel && sel.kind === "proposal"))}>
      {!proposals && <div className="exEmpty" style={{ paddingLeft: pad(1) }}>loading…</div>}
      {err(1)}
      {allRepos
        ? groupByRepo(rows, repos).map((g) => (
          <SubBranch key={g.repo} label={repoName(g.repo)} count={g.rows.length}
                     open={open[rKey(g.repo)] ?? true} onToggle={() => toggle(rKey(g.repo), true)}>
            {g.rows.map((r) => <ProposalRow key={r.id} r={r} depth={2} sel={sel} onSelect={onSelect} />)}
          </SubBranch>))
        : rows.map((r) => <ProposalRow key={r.id} r={r} depth={1} sel={sel} onSelect={onSelect} />)}
      {proposals && !proposals.error && !rows.length && <div className="exEmpty" style={{ paddingLeft: pad(1) }}>none</div>}
    </Branch>
  );
};

// explorer.jsx's Branch, at depth 0 and 1 (kept here so the branch draws on its own in tests).
const Branch = ({ label, count, open, onToggle, children, depth = 0 }) => (
  <>
    <div className="exRow exBranch" style={{ paddingLeft: pad(depth) }} onClick={onToggle}>
      <span className="exChev">{open ? "▾" : "▸"}</span>
      <span className="exLabel">{label}</span>
      {count != null && <span className="exCount">{count}</span>}
    </div>
    {open && children}
  </>
);
const SubBranch = (p) => <Branch {...p} depth={1} />;

const SOURCES = {
  decision: "a decision waits on you",
  flow: "the proposal flow's step",
  doc: "the doc's Status: on the default branch",
  ticket: "an open proposal ticket with no flow and no doc yet",
};

/**
 * What the view says above the pitch: the status and its source, the PR, the
 * build ticket (opens its ticket view), the proposal ticket, and the doc's
 * decision log, oldest first as the doc keeps it.
 */
export const ProposalFacts = ({ row, onSelect, onOpenIssue }) => (
  <div className="proposalFacts">
    <div className="proposalLine">
      <Chip status={row.status} /> <span className="muted">{SOURCES[row.statusSource] || ""}</span>
    </div>
    <div className="proposalLine">
      {[
        row.number != null && (onOpenIssue
          ? <a key="issue" href="#" onClick={(e) => { e.preventDefault(); onOpenIssue(row.repo, row.number); }}>#{row.number} in the Issues view</a>
          : <a key="issue" href={row.url}>#{row.number}</a>),
        row.number != null && onSelect && <a key="ticket" href="#" className="proposalTicket"
          onClick={(e) => { e.preventDefault(); onSelect({ kind: "ticket", id: ticketId(row.repo, row.number) }); }}>ticket view</a>,
        row.prUrl && <a key="pr" href={row.prUrl}>PR</a>,
        row.docPath && <span key="doc" className="muted">{row.repo} {row.docPath}</span>,
        row.buildTicket != null && <span key="build">build: <a href="#" className="proposalBuild"
          onClick={(e) => { e.preventDefault(); onSelect && onSelect({ kind: "ticket", id: ticketId(row.repo, row.buildTicket) }); }}>#{row.buildTicket}</a></span>,
      ].filter(Boolean).flatMap((el, i) => (i ? [<span key={`sep${i}`} className="muted"> · </span>, el] : [el]))}
    </div>
    {row.decisionLog.length > 0 && (
      <details className="proposalLog" open>
        <summary>Decision log</summary>
        <ol>{row.decisionLog.map((l, i) => <li key={i}>{l}</li>)}</ol>
      </details>
    )}
  </div>
);

/**
 * The way from the issue detail and the ticket detail to the proposal view
 * (#660): a "Proposal view" link and the row's status chip, for a ticket the
 * Proposals branch lists; nothing otherwise. `chip={false}` leaves the chip
 * out where ProposalFacts follows and states the status already (#791).
 */
export const ProposalLink = ({ row, onSelect, chip = true }) => (row && onSelect ? (
  <div className="proposalLine proposalLink">
    <a href="#" className="proposalOpen"
       onClick={(e) => { e.preventDefault(); onSelect({ kind: "proposal", id: row.id }); }}>Proposal view</a>
    {chip && <Chip status={row.status} />}
  </div>
) : null);

/**
 * The proposal view: `row` is one of toProposalRows', or null when the id the
 * explorer names is not (or not yet) listed; `loading` while the first list is
 * out. `fleet` is
 * window.fleet (a prop for tests).
 */
export const ProposalView = ({ row, loading, onSelect, onOpenIssue, onChatAbout,
  fleet = typeof window !== "undefined" ? window.fleet : null }) => {
  const [result, setResult] = useState({ loading: true });
  const [open, setOpen] = useState(true);
  const key = row ? `${row.id}|${row.slug || ""}` : "";
  useEffect(() => {
    if (!row) return undefined;
    let live = true;
    setResult({ loading: true });
    setOpen(true);
    loadPitch({ repo: row.repo, number: row.number, slug: row.slug, fleet }).then((r) => { if (live) setResult(r); });
    return () => { live = false; };
  }, [key]);
  if (!row) {
    return <div className="fleet-root"><div className={loading ? "nmEmpty" : "err"}>
      {loading ? "loading…" : "this proposal is not in the list any more; pick another in the explorer"}</div></div>;
  }
  const p = result && result.pitch;
  return (
    <div className="fleet-root proposalView">
      <h2>{row.title} <span className="muted">· {row.repo}</span></h2>
      <ProposalFacts row={row} onSelect={onSelect} onOpenIssue={onOpenIssue} />
      <h2 className="tdFold" onClick={() => setOpen((o) => !o)} title={open ? "Hide the pitch" : "Show the pitch"}>
        <FoldArrow open={open} />Pitch{p ? <span className="muted"> · {p.kind === "html" ? p.path : `${p.path}, no pitch page`}</span> : null}</h2>
      <div className="pitchView">
        <DecisionControls key={row.id} enabled={!!row.decision && row.number != null} repo={row.repo} number={row.number} fleet={fleet}
                          onChatAbout={onChatAbout && row.number != null
                            ? () => onChatAbout({ repo: row.repo, number: row.number, title: row.title }) : null} />
        {open && <div className="pitchMain"><PitchBody result={result} /></div>}
      </div>
    </div>
  );
};
