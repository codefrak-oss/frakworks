// Tickets > Tickets, Board view (#100094): the same rows as the table, laid
// out as cards in one column per stage of the engine's work. Internally the
// "stage board", so it does not collide with the issues board (useBoardRows,
// applyBoardRow), which feeds it.
//
// The column set is fixed (layout A): Intake, Designing, Coding, Building,
// Reviewing, Merging, Waiting. When the pane is too narrow for all seven, the
// empty columns go (layout B), and narrower still the columns stack as
// collapsible lanes; both are container queries on .sbWrap in styles.css,
// so the pane's width decides, not the window's. A card whose ticket has a
// Smith running (isSmithRunning, from the row's own fields: no request per
// card) carries a stripe sweeping along its bottom edge.
import { STAGE_SEGMENTS, HOLDS, stateOf, childrenSummary, TicketAge } from "./issues.jsx";

export const STAGE_COLUMNS = [
  { id: "intake", label: "Intake" },
  { id: "designing", label: "Designing" },
  { id: "coding", label: "Coding" },
  { id: "building", label: "Building" },
  { id: "reviewing", label: "Reviewing" },
  { id: "merging", label: "Merging" },
  { id: "waiting", label: "Waiting" },
];
export const WAITING = "waiting";

// The column of each STAGE_SEGMENTS row, per kind, in the same order.
export const SEGMENT_COLUMNS = {
  chore: ["intake", "coding", "building", "building", "reviewing", "merging", "merging"],
  bug: ["intake", "intake", "coding", "building", "building", "reviewing", "merging", "merging", "merging", "merging"],
  feature: ["intake", "designing", "coding", "building", "building", "reviewing", "merging", "merging", "merging", "merging", "coding"],
  intake: ["intake", "intake", "intake", "intake", "intake"],
  research: ["intake", "intake", "intake", "intake", "intake"],
  proposal: ["intake", "intake", "reviewing", "building", "merging", "merging", "merging"],
};

const flowKind = (row) => {
  const f = row && row.flow;
  return f && STAGE_SEGMENTS[f.flow] ? f.flow : row && row.kind;
};

/** The column id a board row's card lands in: no flow, an ended flow or an unknown stage is Waiting. */
export const stageColumnOf = (row) => {
  const f = row && row.flow;
  if (!f || f.ended || !f.stage) return WAITING;
  const kind = flowKind(row);
  const rows = STAGE_SEGMENTS[kind];
  if (!rows) return WAITING;
  const i = rows.findIndex(([ids]) => ids.includes(f.stage));
  return i < 0 ? WAITING : SEGMENT_COLUMNS[kind][i] || WAITING;
};

/** A Smith runs for the row: its flow is live, not on an incident, not asking, not at a stage that waits on someone. */
export const isSmithRunning = (row) => {
  const f = row && row.flow;
  return !!(f && !f.ended && f.stage && f.incident == null && !row.pendingQuestion && !HOLDS.test(f.stage));
};

/** The rows grouped into every column, in STAGE_COLUMNS order: [{ id, label, rows }]. */
export const groupByColumn = (rows) => STAGE_COLUMNS.map((c) => ({ ...c, rows: (rows || []).filter((r) => stageColumnOf(r) === c.id) }));

const holdOf = (row) => {
  const f = row.flow;
  if (f && f.incident != null) return { mark: "!", title: "incident" };
  if (row.pendingQuestion || (f && !f.ended && f.stage && HOLDS.test(f.stage))) return { mark: "⏸", title: "waiting on someone" };
  return null;
};

const Card = ({ row, onOpenTicket, showRepo, now }) => {
  const state = stateOf(row);
  const running = isSmithRunning(row);
  const hold = holdOf(row);
  const open = (e) => {
    if (!onOpenTicket || e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
    e.preventDefault();
    onOpenTicket(row.number, row.repo);
  };
  return (
    <a className={"sbCard" + (running ? " sbRunning" : "") + (hold ? " sbHold" : "")} href={row.url} title={row.url} onClick={open}>
      <div className="sbTop">
        <span className="sbNum">{row.number == null ? "ask" : `#${row.number}`}</span>
        {showRepo && <span className="sbRepo muted">{row.repo}</span>}
        <TicketAge createdAt={row.createdAt} now={now} />
        {hold && <span className="sbMark" title={hold.title}>{hold.mark}</span>}
      </div>
      <div className="sbTitle">{row.title}</div>
      <div className="isLabels">
        {(row.labels || []).map((l) => <span key={l} className="isLabel">{l}</span>)}
        {Number.isInteger(row.points) && <span className="isLabel isPoints">{row.points} pts</span>}
        {row.parent && <span className="isLabel isPart">part of #{row.parent.number}</span>}
      </div>
      {childrenSummary(row) && <div className="sbParts muted">{childrenSummary(row)}</div>}
      <div className="sbState muted">{state.text}</div>
      {running && <span className="sbSweep" aria-hidden="true" />}
    </a>
  );
};

export const StageBoard = ({ issues, onOpenTicket, now }) => {
  const showRepo = new Set((issues || []).map((i) => i.repo)).size > 1;
  return (
    <div className="sbWrap"><div className="sbBoard">
      {groupByColumn(issues).map((c) => (
        <details key={c.id} open className={`sbCol sb-${c.id}` + (c.rows.length ? "" : " sbEmpty")}>
          <summary className="sbHead">{c.label} ({c.rows.length})</summary>
          {c.rows.map((r) => <Card key={r.number == null ? r.id : `${r.repo}#${r.number}`} row={r} onOpenTicket={onOpenTicket} showRepo={showRepo} now={now} />)}
        </details>
      ))}
    </div></div>
  );
};
