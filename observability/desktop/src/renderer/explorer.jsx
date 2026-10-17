// explorer.jsx — the left-hand tree, after VS Code's Explorer. Drawing only:
// the groups and saved chats arrive as props, and every click is a callback. The
// open/closed state of each branch is the one thing it keeps itself.
import { useState } from "react";
import { ticketId } from "./repo-switcher.jsx";
import { ProposalsBranch } from "./proposals.jsx";
import { useRepoName } from "./repo-name-form.jsx";
import { ReposBranch } from "./repo-files.jsx";

// `sel` is what the work area shows: { kind: "tickets" | "accounts" | "repos" | "frakworks" |
// "jev" | "doctor" | "friction" | "refactoring" | "ssh-verdicts" | "grafana" | "builds" | "hitlp-producer" | "hitlp-requests" | "hitlp-closed" | "needs" | "ticket" | "proposal" | "saved-chat", id? }. A row is highlighted when it names the same thing.
// A ticket's id is `owner/name#n` (#577); a proposal's is its ticket's, or `owner/name:<slug>` for a doc with none (#660).
const same = (a, b) => a && b && a.kind === b.kind && String(a.id ?? "") === String(b.id ?? "");

// A leaf keeps the chevron's width empty, so its label lines up with a sibling branch's.
// `badge` is the yellow count (zero draws none), `count` a plain one.
export const Row = ({ depth, sel, item, onSelect, children, badge, count, title, aside }) => (
  <div className={"exRow" + (same(sel, item) ? " on" : "")} style={{ paddingLeft: 8 + depth * 14 }}
       title={title} onClick={() => onSelect(item)}>
    <span className="exChev" />
    <span className="exLabel">{children}</span>
    {aside}
    {count != null && <span className="exCount">{count}</span>}
    {badge != null && badge !== 0 && <span className="exBadge">{badge}</span>}
  </div>
);

// `extra` is drawn on the row after the count.
// With `item` the label selects it (`onSelect`) and the chevron alone toggles;
// without, a click anywhere on the row toggles. `badge` is the yellow count.
export const Branch = ({ depth, label, count, badge, open, onToggle, children, extra, title, item = null, sel = null, onSelect = null }) => (
  <>
    <div className={"exRow exBranch" + (item && same(sel, item) ? " on" : "")} style={{ paddingLeft: 8 + depth * 14 }} title={title}
         onClick={item ? undefined : onToggle}>
      <span className="exChev" onClick={item ? (e) => { e.stopPropagation(); onToggle(); } : undefined}>{open ? "▾" : "▸"}</span>
      <span className="exLabel" onClick={item ? () => onSelect && onSelect(item) : undefined}>{label}</span>
      {count != null && <span className="exCount">{count}</span>}
      {badge != null && badge !== 0 && <span className="exBadge">{badge}</span>}
      {extra}
    </div>
    {open && children}
  </>
);

const DONE_PAGE = 50; // src/ticket-tree.js DONE_PAGE: what "more" adds

/** One closed ticket: greyed when closed as not planned, what closed it on the right, and `part of #n` for a split's part (Done stays chronological). */
// `repo` is the Done list's repo (null in All repos, where a row names its own).
const DoneRow = ({ t, repo, sel, onSelect }) => (
  <Row depth={3} sel={sel} item={{ kind: "ticket", id: ticketId(t.repo || repo, t.number) }} onSelect={onSelect}
       title={t.title + (t.unfinished ? " (closed as not planned)" : "") + (t.parent ? ` (part of #${t.parent})` : "")}
       aside={<span className="exOutcome">{t.pr ? `✓ PR #${t.pr}` : t.unfinished ? "not planned" : "✓"}</span>}>
    <span className={t.unfinished ? "exUnfinished" : undefined}>
      <span className="exNum">{repo ? "" : t.repo}#{t.number}</span> {t.title}
    </span>
    {t.parent && <span className="exSuffix"> part of #{t.parent}</span>}
  </Row>
);

/**
 * Closed tickets, by when they closed. Asks for nothing until it is first
 * opened (`onLoad`), and "more" asks for a longer list; `done` is the answer,
 * the engine's search result when it carries a `q`.
 */
export const DoneBranch = ({ done, open, toggle, onLoad, sel, onSelect }) => {
  const onToggle = () => {
    if (!open.done && !done) onLoad(DONE_PAGE);
    toggle("done");
  };
  const buckets = (done && done.buckets) || [];
  const searching = !!(done && done.q);
  const loaded = buckets.reduce((n, b) => n + b.tickets.length, 0);
  return (
    <Branch depth={1} label="Done" count={done && !done.error ? `${loaded}${done.more ? "+" : ""}` : null}
            open={!!open.done} onToggle={onToggle}>
      {(!done || (done.loading && !loaded)) && <div className="exEmpty" style={{ paddingLeft: 36 }}>loading…</div>}
      {done && done.error && <div className="exErr" style={{ paddingLeft: 36 }}>{done.error}</div>}
      {buckets.filter((b) => b.tickets.length).map((b) => (
        <Branch key={b.id} depth={2} label={b.label} count={b.tickets.length}
                open={open[`d:${b.id}`] ?? b.id === "today"} onToggle={() => toggle(`d:${b.id}`, b.id === "today")}>
          {b.tickets.map((t) => <DoneRow key={ticketId(t.repo || done.repo, t.number)} t={t} repo={done.repo || null}
                                         sel={sel} onSelect={onSelect} />)}
        </Branch>
      ))}
      {done && !done.error && !done.loading && !loaded && <div className="exEmpty" style={{ paddingLeft: 36 }}>{searching ? "no match" : "none"}</div>}
      {done && done.more && (
        <div className="exRow exMore" style={{ paddingLeft: 8 + 2 * 14 }}
             onClick={() => !done.loading && onLoad((done.limit || DONE_PAGE) + DONE_PAGE)}>
          {done.loading ? "loading…" : `load ${DONE_PAGE} more`}
        </div>
      )}
    </Branch>
  );
};

/**
 * Analytics' Grafana rows (#332): one per configured dashboard, `sel` naming
 * one by its index. A malformed entry, or a broken Grafana block, shows its
 * own error in place of a row.
 */
export const GrafanaRows = ({ grafana, sel, onSelect }) => (
  <>
    {grafana && grafana.error && <div className="exErr" style={{ paddingLeft: 22 }}>Grafana: {grafana.error}</div>}
    {((grafana && grafana.dashboards) || []).map((d, i) => d.url
      ? <Row key={i} depth={1} sel={sel} item={{ kind: "grafana", id: i }} onSelect={onSelect} title={d.url}>{d.label}</Row>
      : <div key={i} className="exErr" style={{ paddingLeft: 22 }}>Grafana: {d.error}</div>)}
  </>
);

const pad = (depth) => 8 + depth * 14;

/** One faint vertical line per nesting level under a root ticket, under the chevron column, as VS Code draws. */
const Guides = ({ base, level }) => Array.from({ length: level }, (_, k) => (
  <span key={k} className="exGuide" style={{ left: pad(base + k) + 5 }} />
));

/** A row of a split that is no ticket on the open board: a closed part (selectable), a pending draft or a dropped one. */
const DraftRow = ({ c, repo, base, level, sel, onSelect }) => {
  const style = { paddingLeft: pad(base + level) };
  if (c.draft === "closed" && c.number != null) {
    const item = { kind: "ticket", id: ticketId(repo, c.number) };
    return (
      <div className={"exRow exNode" + (same(sel, item) ? " on" : "")} style={style} title={c.title} onClick={() => onSelect(item)}>
        <Guides base={base} level={level} />
        <span className="exChev" />
        <span className="exLabel"><span className="exNum">#{c.number}</span> {c.title}</span>
        <span className="exOutcome">{c.prNumber ? `✓ PR #${c.prNumber}` : "✓"}</span>
      </div>
    );
  }
  return (
    <div className={"exRow exNode exDraft" + (c.draft === "dropped" ? " exDropped" : "")} style={style}
         title={c.title + (c.draft === "dropped" ? " (dropped)" : " (not filed yet)")}>
      <Guides base={base} level={level} />
      <span className="exChev" />
      <span className="exLabel">{c.draft === "dropped" ? <span className="exUnfinished">{c.title}</span> : c.title}</span>
      {c.draft !== "dropped" && <span className="exSuffix">{c.draft === "closed" ? "✓" : "not filed"}</span>}
    </div>
  );
};

/**
 * One ticket of the tree and, while open, its parts (#571). The chevron
 * toggles `t:<repo>#<n>` and never selects; the row selects. `expand`: the family sits in Needs you, so a
 * node over the member that needs the user starts open. `base` is the depth of the family's root row.
 */
export const TicketNode = ({ t, repo, base, level, expand, open, toggle, sel, onSelect }) => {
  const item = { kind: "ticket", id: ticketId(t.repo || repo, t.number) };
  const parent = t.children.length > 0;
  const key = `t:${item.id}`;
  const dflt = !!(expand && t.urgency);
  const isOpen = parent && (open[key] ?? dflt);
  const tip = t.title + (t.intake ? ` (via #${t.intake.number})` : "") + (t.needsYou === "question" ? " (a question waits on you)"
    : t.needsYou === "incident" ? " (stuck on an incident)" : "");
  return (
    <>
      <div className={"exRow exNode" + (same(sel, item) ? " on" : "")} style={{ paddingLeft: pad(base + level) }}
           title={tip} onClick={() => onSelect(item)}>
        <Guides base={base} level={level} />
        {parent
          ? <span className="exChev exTwisty" title={isOpen ? "Collapse" : "Expand"}
                  onClick={(e) => { e.stopPropagation(); toggle(key, dflt); }}>{isOpen ? "▾" : "▸"}</span>
          : <span className="exChev" />}
        <span className="exLabel">
          <span className="exNum">#{t.number}</span> {t.title}
          {t.pitch && <span className="exPitch" title={t.pitch}>pitch</span>}
          {t.partOf && <span className="exSuffix"> part of #{t.partOf.number}{t.partOf.closed ? " (closed)" : ""}</span>}
          {t.blockers && t.blockers.length > 0 && <span className="exSuffix"> ⧗ {t.blockers.map((b) => `#${b}`).join(" ")}</span>}
        </span>
        {parent && !isOpen && t.urgency && <span className={"exDot " + t.urgency}
          title={t.urgency === "crit" ? "a part is stuck on an incident" : "a part needs you"} />}
        {t.partsTotal > 0 && <span className="exCount">{t.partsDone}/{t.partsTotal}</span>}
        {t.needsYou && <span className={"exBadge" + (t.needsYou === "incident" ? " crit" : "")}>{t.needsYou === "incident" ? "!" : "?"}</span>}
      </div>
      {isOpen && t.children.map((c) => c.draft
        ? <DraftRow key={`d:${c.index}`} c={c} repo={t.repo || repo} base={base} level={level + 1} sel={sel} onSelect={onSelect} />
        : <TicketNode key={c.number} t={c} repo={repo} base={base} level={level + 1} expand={expand}
                      open={open} toggle={toggle} sel={sel} onSelect={onSelect} />)}
    </>
  );
};

/** One repo's open tickets, by state, each family once in its most urgent group: `repo` is theirs, `depth` where the groups sit. */
const TicketGroups = ({ groups, repo, depth, keyOf, open, toggle, sel, onSelect }) => groups.map((g) => (
  <Branch key={g.id} depth={depth} label={g.label} count={g.count ?? g.tickets.length}
          open={!!open[keyOf(g.id)]} onToggle={() => toggle(keyOf(g.id))}>
    {g.tickets.map((t) => (
      <TicketNode key={t.number} t={{ children: [], ...t }} repo={repo} base={depth + 1} level={0} expand={g.id === "needs-you"}
                  open={open} toggle={toggle} sel={sel} onSelect={onSelect} />
    ))}
    {!g.tickets.length && <div className="exEmpty" style={{ paddingLeft: 8 + (depth + 1) * 14 }}>none</div>}
  </Branch>
));

/** The tree entry for ticket `ref` ({ repo, number }) in a fetchTicketTree answer, or null: the ticket view's "Intake #n". */
export const treeEntryOf = (tree, ref) => {
  if (!tree || !ref) return null;
  const sets = Array.isArray(tree.repos) ? tree.repos : [{ repo: tree.repo, groups: tree.groups || [] }];
  const find = (nodes) => {
    for (const n of nodes || []) {
      if (n.draft) continue;
      if (n.number === ref.number) return n;
      const hit = find(n.children);
      if (hit) return hit;
    }
    return null;
  };
  for (const r of sets) {
    if (ref.repo && r.repo && String(r.repo).toLowerCase() !== String(ref.repo).toLowerCase()) continue;
    for (const g of r.groups || []) { const hit = find(g.tickets); if (hit) return hit; }
  }
  return null;
};

/** When a chat was saved, short: the time today, else the date. */
export const savedWhen = (iso, now = new Date()) => {
  const d = new Date(iso);
  if (!iso || Number.isNaN(d.getTime())) return "";
  return d.toDateString() === now.toDateString()
    ? d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
    : d.toLocaleDateString();
};

/**
 * Saved First mate chats (#598): `savedChats` is `{ chats, error }` from the
 * engine's `GET /saved-chats`, newest saved first; each row opens its view.
 */
const CC_FOLDERS = [["recent15", "Recent 15"], ["today", "Today"], ["lastMonth", "Last month"]];

/**
 * Saved Chats > Claude Code (#1708): the local Claude Code sessions,
 * `{ recent15, today, lastMonth, byDirectory, error }` from main (src/claude-code-sessions.js),
 * null while loading. Each row shows the first message, the project folder and
 * when it was last active, and opens the read-only session view.
 */
const ccRow = (r, depth, sel, onSelect) => (
  <Row key={r.id} depth={depth} sel={sel} item={{ kind: "claude-session", id: r.id }} onSelect={onSelect}
       title={`${r.title}\n${r.cwd || r.project} — last active ${new Date(r.lastAt).toLocaleString()}`}
       aside={<span className="exOutcome">{r.project} · {savedWhen(r.lastAt)}</span>}>
    {r.title}
  </Row>
);

const ccNone = (depth) => <div className="exEmpty" style={{ paddingLeft: 8 + depth * 14 }}>none</div>;

export const ClaudeCodeBranch = ({ sessions, open, toggle, sel, onSelect, depth = 1 }) => {
  const dirs = (sessions && sessions.byDirectory) || [];
  return (
    <Branch depth={depth} label="Claude Code" open={open["cc"] ?? false} onToggle={() => toggle("cc", false)}>
      {!sessions && <div className="exEmpty" style={{ paddingLeft: 8 + (depth + 1) * 14 }}>loading…</div>}
      {sessions && sessions.error && <div className="exErr" style={{ paddingLeft: 8 + (depth + 1) * 14 }}>{sessions.error}</div>}
      {sessions && CC_FOLDERS.map(([k, label]) => {
        const rows = sessions[k] || [];
        return (
          <Branch key={k} depth={depth + 1} label={label} count={rows.length}
                  open={open[`cc:${k}`] ?? false} onToggle={() => toggle(`cc:${k}`, false)}>
            {rows.map((r) => ccRow(r, depth + 2, sel, onSelect))}
            {!rows.length && ccNone(depth + 2)}
          </Branch>
        );
      })}
      {sessions && (
        <Branch depth={depth + 1} label="By directory (last 24 hours)" count={dirs.reduce((n, g) => n + g.sessions.length, 0)}
                open={open["cc:byDirectory"] ?? false} onToggle={() => toggle("cc:byDirectory", false)}>
          {dirs.map((g) => (
            <Branch key={g.cwd} depth={depth + 2} label={g.project} title={g.cwd} count={g.sessions.length}
                    open={open[`cc:byDirectory:${g.cwd}`] ?? false} onToggle={() => toggle(`cc:byDirectory:${g.cwd}`, false)}>
              {g.sessions.map((r) => ccRow(r, depth + 3, sel, onSelect))}
            </Branch>
          ))}
          {!dirs.length && ccNone(depth + 2)}
        </Branch>
      )}
    </Branch>
  );
};

export const SavedChatsBranch = ({ savedChats, open, toggle, sel, onSelect, claudeSessions }) => {
  const chats = (savedChats && savedChats.chats) || [];
  return (
    <Branch depth={0} label="Saved Chats" count={savedChats && !savedChats.error ? chats.length : null}
            open={open.saved ?? true} onToggle={() => toggle("saved", true)}>
      {!savedChats && <div className="exEmpty" style={{ paddingLeft: 22 }}>loading…</div>}
      {savedChats && savedChats.error && <div className="exErr" style={{ paddingLeft: 22 }}>{savedChats.error}</div>}
      {chats.map((c) => (
        <Row key={c.id} depth={1} sel={sel} item={{ kind: "saved-chat", id: c.id }} onSelect={onSelect}
             title={`${c.title} (saved ${c.savedAt ? new Date(c.savedAt).toLocaleString() : "?"})`}
             aside={<span className="exOutcome">{savedWhen(c.savedAt)}</span>}>
          {c.title}
        </Row>
      ))}
      {savedChats && !savedChats.error && !chats.length && <div className="exEmpty" style={{ paddingLeft: 22 }}>none</div>}
      {claudeSessions !== undefined && <ClaudeCodeBranch sessions={claudeSessions} open={open} toggle={toggle} sel={sel} onSelect={onSelect} />}
    </Branch>
  );
};

const CONFIG_KINDS = ["accounts", "repos", "jobs", "frakworks", "palettes"];
const ANALYTICS_KINDS = ["jev", "doctor", "friction", "refactoring", "ssh-verdicts", "account-usage", "account-consumption", "grafana", "builds"];
const UTILS_KINDS = ["hitlp-producer"];

/**
 * `tree` is src/ticket-tree.js `fetchTicketTree`'s answer: `{ repo, groups }`
 * for one repo, or in All repos `{ repos: [{ repo, groups }] }`, drawn as a
 * branch per repo. `proposals` is the Proposals branch's `{ rows, error, failures }`
 * (proposals.jsx), null while loading; `repos` the target repos, its sub-branches' order.
 * `browseRepos` is the repos the Repos branch browses (repo-files.jsx): the
 * switcher's repo, or every target repo under All repos; null while loading.
 */
// `hitlpOpen` and `hitlpClosed` are the HITLP server's open and decided task counts (null while unread).
export const Explorer = ({ sel, onSelect, tree, needsCount, hitlpOpen = null, hitlpClosed = null, onRefreshTree, done, onLoadDone, grafana, savedChats, claudeSessions, proposals, repos, browseRepos }) => {
  // Config, Analytics and Utils start collapsed (HITLP Requests and Tickets open) unless the page restored on launch is under one of them.
  const [open, setOpen] = useState(() => ({ config: CONFIG_KINDS.includes(sel && sel.kind), analytics: ANALYTICS_KINDS.includes(sel && sel.kind), utils: UTILS_KINDS.includes(sel && sel.kind), tickets: true, "g:needs-you": true, "g:in-progress": true, "g:deferred": true }));
  // `dflt` is what an unset key stands for, so a branch open by default closes on its first click.
  const toggle = (k, dflt = false) => setOpen((o) => ({ ...o, [k]: !(o[k] ?? dflt) }));
  const perRepo = tree && Array.isArray(tree.repos) ? tree.repos : null;
  const repoName = useRepoName(perRepo ? perRepo.map((r) => r.repo) : repos);
  const groups = (tree && tree.groups) || [];
  const count = (gs) => gs.reduce((n, g) => n + (g.count ?? g.tickets.length), 0);
  const total = perRepo ? perRepo.reduce((n, r) => n + count(r.groups || []), 0) : count(groups);
  // Group keys are shared across repos, so "needs you" opens the same way in each.
  const gKey = (id) => `g:${id}`;
  const rKey = (repo) => `r:${repo}`;
  return (
    <nav className="explorer">
      <div className="exHead">
        <span>EXPLORER</span>
        <button className="exRefresh" title="Refresh tickets" onClick={onRefreshTree}>⟳</button>
      </div>
      <Branch depth={0} label="Config" open={open.config} onToggle={() => toggle("config")}>
        <Row depth={1} sel={sel} item={{ kind: "accounts" }} onSelect={onSelect}>AI Accounts</Row>
        <Row depth={1} sel={sel} item={{ kind: "repos" }} onSelect={onSelect}>Repos</Row>
        <Row depth={1} sel={sel} item={{ kind: "jobs" }} onSelect={onSelect}>Jobs</Row>
        <Row depth={1} sel={sel} item={{ kind: "frakworks" }} onSelect={onSelect}>frakworks</Row>
        <Row depth={1} sel={sel} item={{ kind: "palettes" }} onSelect={onSelect}>Palettes</Row>
      </Branch>
      <Branch depth={0} label="Analytics" open={open.analytics} onToggle={() => toggle("analytics")}>
        <Branch depth={1} label="AI Accounts" open={open["ai-accounts"] ?? true} onToggle={() => toggle("ai-accounts", true)}>
          <Row depth={2} sel={sel} item={{ kind: "account-usage" }} onSelect={onSelect}>Account balances</Row>
          <Row depth={2} sel={sel} item={{ kind: "account-consumption" }} onSelect={onSelect}>Account consumption</Row>
        </Branch>
        <Row depth={1} sel={sel} item={{ kind: "jev" }} onSelect={onSelect}>Jev decisions</Row>
        <Row depth={1} sel={sel} item={{ kind: "doctor" }} onSelect={onSelect}>Doctor</Row>
        <Row depth={1} sel={sel} item={{ kind: "friction" }} onSelect={onSelect}>Friction notes</Row>
        <Row depth={1} sel={sel} item={{ kind: "refactoring" }} onSelect={onSelect}>Refactoring</Row>
        <Row depth={1} sel={sel} item={{ kind: "ssh-verdicts" }} onSelect={onSelect}>SSH verdicts</Row>
        <GrafanaRows grafana={grafana} sel={sel} onSelect={onSelect} />
        <Row depth={1} sel={sel} item={{ kind: "builds" }} onSelect={onSelect}>Builds</Row>
      </Branch>
      <Branch depth={0} label="Utils" open={open.utils} onToggle={() => toggle("utils")}>
        <Row depth={1} sel={sel} item={{ kind: "hitlp-producer" }} onSelect={onSelect}>HITLP producer</Row>
      </Branch>
      <Branch depth={0} label="HITLP Requests" badge={hitlpOpen} open={open.hitlp ?? true} onToggle={() => toggle("hitlp", true)}
              item={{ kind: "hitlp-requests" }} sel={sel} onSelect={onSelect}>
        <Row depth={1} sel={sel} item={{ kind: "hitlp-closed" }} onSelect={onSelect} count={hitlpClosed}>Closed</Row>
      </Branch>
      <Branch depth={0} label="Tickets" count={tree ? total : "…"} open={open.tickets} onToggle={() => toggle("tickets")}
              item={{ kind: "tickets" }} sel={sel} onSelect={onSelect}>
        <Row depth={1} sel={sel} item={{ kind: "needs" }} onSelect={onSelect} badge={needsCount}>Needs you</Row>
        {tree && tree.error && <div className="exErr" style={{ paddingLeft: 22 }}>{tree.error}</div>}
        {perRepo
          ? perRepo.map((r) => (
            <Branch key={r.repo} depth={1} label={repoName(r.repo)} count={count(r.groups || [])}
                    open={open[rKey(r.repo)] ?? true} onToggle={() => toggle(rKey(r.repo), true)}>
              <TicketGroups groups={r.groups || []} repo={r.repo} depth={2} keyOf={gKey}
                            open={open} toggle={toggle} sel={sel} onSelect={onSelect} />
            </Branch>))
          : <TicketGroups groups={groups} repo={tree && tree.repo} depth={1} keyOf={gKey}
                          open={open} toggle={toggle} sel={sel} onSelect={onSelect} />}
        {onLoadDone && <DoneBranch done={done} open={open} toggle={toggle} onLoad={onLoadDone} sel={sel} onSelect={onSelect} />}
      </Branch>
      <ProposalsBranch proposals={proposals} allRepos={!!perRepo}
                       repos={repos} open={open} toggle={toggle} sel={sel} onSelect={onSelect} />
      <ReposBranch repos={browseRepos} sel={sel} onSelect={onSelect} />
      <SavedChatsBranch savedChats={savedChats} claudeSessions={claudeSessions} open={open} toggle={toggle} sel={sel} onSelect={onSelect} />
    </nav>
  );
};
