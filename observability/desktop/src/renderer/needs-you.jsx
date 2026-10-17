// needs-you.jsx — the "things you are needed for" view.
//
// One row per thing waiting on a person, grouped by its kind, with its ask,
// how long it has waited, and a link to its ticket: the engine's `GET /needs`
// rows only. The HITLP server's tasks are HITLP Requests' (hitlp-requests.jsx),
// which draws them with hitlp-task.jsx's `HitlpTask`; this file keeps the
// helpers that view shares (buttonsFor, parseTyped, surfaceDecide, Countdown)
// and writes nothing itself.
//
// Ordering lives in ../needs-you.js (main process); this file only draws what
// it returns, so the ordering rules are testable without a DOM.

import { useEffect, useState } from "react";
import { RESTARTING_VIEW } from "./engine-events.js";

// The HITLP server's task kinds (#2173): HITLP Requests', never a Needs section.
export const HITLP_KINDS = ["ask", "approve"];

// One section per kind of Need the engine reports, in the order drawn. Today's
// kinds are labelled in HITLP's vocabulary (#2173): a question or a decision
// is an Ask, an authorization an Approve.
export const KIND_TITLES = {
  question: "Ask: a question is waiting",
  acceptance: "Waiting for acceptance",
  decision: "Ask: waiting for your decision",
  incident: "A flow hit an incident",
  // Filed unlabeled by an intake the fleet could not do (#730): the ask is the blocker, the link the ticket.
  "insufficient-permissions": "Can't do with current permissions",
  reauthorize: "Waiting to be re-authorized",
  authorize: "Approve: waiting to be authorized",
};
const KINDS = Object.keys(KIND_TITLES);

/**
 * How many tickets the Needs rows name, the explorer's Needs you badge: a
 * ticket with two rows counts once, a row with no ticket (an incident) once by
 * its `actionId`. HITLP tasks never count here.
 */
export const needsTicketCount = (rows) => new Set((Array.isArray(rows) ? rows : [])
  .filter((r) => r && !HITLP_KINDS.includes(r.kind))
  .map((r) => (r.ticket != null ? `${r.repo || ""}#${r.ticket}` : `action:${r.actionId}`))).size;

// "4d 2h" / "3h 07m" / "12m" — tabular.
export const fmtWaited = (secs) => {
  const s = Math.max(0, Math.round(secs || 0));
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60);
  if (d) return `${d}d ${h}h`;
  if (h) return `${h}h ${String(m).padStart(2, "0")}m`;
  return `${m}m`;
};

// A row with a ticket gets the in-app issue detail on a plain left click and
// "Pin to a chat", in whichever target repo it is (#577); same click guard as
// the Issues rows (issues.jsx): a modifier key
// or non-primary button leaves the link alone so it opens GitHub as normal.
// With one repo selected (`repo`) a row reads `#n`; in All repos (null) it
// names its repo. An incident with no issue has no link at all.
export const Row = ({ row, repo = null, onOpenTicket }) => {
  const rowRepo = row.repo || repo;
  const local = row.ticket != null && !!rowRepo;
  const label = row.ticket == null ? "incident"
    : repo && rowRepo === repo ? `#${row.ticket}` : `${rowRepo}#${row.ticket}`;
  return (
    <div className="nmRow">
      {row.url
        ? <a className="nmTicket" href={row.url} title={row.url}
             onClick={onOpenTicket && local ? (e) => {
               if (e.metaKey || e.ctrlKey || e.shiftKey || e.button > 0) return;
               e.preventDefault();
               onOpenTicket(rowRepo, row.ticket);
             } : undefined}>{label}</a>
        : <span className="nmTicket">{label}</span>}
      <div className="nmBody">
        <div className="nmTitle">{row.title}</div>
        <div className="nmAsk">{row.ask}</div>
      </div>
      <div className="nmSide">
        <span className="nmWait" title={row.source || ""}>{fmtWaited(row.waited)}</span>
      </div>
    </div>
  );
};

// "2h 14m left", or "overdue" once the deadline (epoch seconds) is past; null
// for a row with no deadline.
export const fmtDeadline = (deadline, now) => {
  if (deadline == null) return null;
  const left = deadline - now;
  return left <= 0 ? "overdue" : `${fmtWaited(left)} left`;
};

/** The deadline, re-drawn each minute so the countdown moves without a refetch. */
export const Countdown = ({ deadline, now: fixedNow = null }) => {
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (fixedNow != null || deadline == null) return undefined;
    const t = setInterval(() => setTick((n) => n + 1), 60000);
    return () => clearInterval(t);
  }, [deadline, fixedNow]);
  const now = fixedNow != null ? fixedNow : Math.floor(Date.now() / 1000);
  const text = fmtDeadline(deadline, now);
  if (text == null) return null;
  const overdue = text === "overdue";
  return <span className={"nmDeadline" + (overdue ? " overdue" : "")} data-tick={tick}
    title={`deadline ${new Date(deadline * 1000).toISOString()}`}>{text}</span>;
};

const schemaType = (schema) => {
  if (!schema || typeof schema !== "object") return null;
  const t = Array.isArray(schema.type) ? schema.type.find((x) => x !== "null") : schema.type;
  return typeof t === "string" ? t : null;
};
const optionOf = (o) => {
  if (o && typeof o === "object") {
    const value = o.value !== undefined ? o.value : o.id !== undefined ? o.id : o.label;
    return { value, label: String(o.label !== undefined ? o.label : value) };
  }
  return { value: o, label: String(o) };
};

/**
 * What an `ask` or `approve` row answers with (#2173), a pure function of the
 * row so the tests can read it: `{ buttons: [{ label, outcome, answer }],
 * input: null | "text" | "number" | "json", reason: bool, schemaShown: bool }`.
 * An approve gives Approve and Reject with an optional reason. An ask with
 * `options` (or a schema `enum`) gives one button per entry, then, unless the
 * schema enumerates them, a Something else button (`other: true`) whose text
 * box (`input: "text"`) is shown only once it is picked; a boolean schema
 * Yes and No; a string schema a text box; a number or integer schema a number
 * box; any other shape (an object schema, none at all) shows the schema as
 * JSON and takes a text box whose JSON is sent when it parses, else the text.
 */
export const SOMETHING_ELSE = "Something else";
export const buttonsFor = (row) => {
  if (!row) return { buttons: [], input: null, reason: false, schemaShown: false };
  if (row.kind === "approve") {
    return { buttons: [{ label: "Approve", outcome: "approved" }, { label: "Reject", outcome: "rejected" }], input: null, reason: true, schemaShown: false };
  }
  const schema = row.responseSchema;
  const options = Array.isArray(row.options) && row.options.length ? row.options
    : schema && Array.isArray(schema.enum) && schema.enum.length ? schema.enum : null;
  if (options) {
    const buttons = options.map(optionOf).map((o) => ({ label: o.label, outcome: "answered", answer: o.value }));
    // A schema that enumerates the values (a proposal's or a research ack's verbs) takes nothing else.
    if (schema && Array.isArray(schema.enum)) return { buttons, input: null, reason: false, schemaShown: false };
    return { buttons: [...buttons, { label: SOMETHING_ELSE, outcome: "answered", other: true }], input: "text", reason: false, schemaShown: false };
  }
  const type = schemaType(schema);
  if (type === "boolean") {
    return { buttons: [{ label: "Yes", outcome: "answered", answer: true }, { label: "No", outcome: "answered", answer: false }], input: null, reason: false, schemaShown: false };
  }
  if (type === "string") return { buttons: [{ label: "Answer", outcome: "answered" }], input: "text", reason: false, schemaShown: false };
  if (type === "number" || type === "integer") return { buttons: [{ label: "Answer", outcome: "answered" }], input: "number", reason: false, schemaShown: false };
  return { buttons: [{ label: "Answer", outcome: "answered" }], input: "json", reason: false, schemaShown: schema != null };
};

// The typed answer as the value sent: a number box parses with Number()
// (refused when it is not one), a json box sends the parsed JSON when the
// text parses and the text itself otherwise, a text box the text.
export const parseTyped = (input, text) => {
  const t = String(text ?? "");
  if (input === "number") {
    const n = t.trim() === "" ? NaN : Number(t);
    return Number.isFinite(n) ? { answer: n } : { error: "the answer must be a number" };
  }
  if (input === "json") {
    if (!t.trim()) return { error: "type an answer first" };
    try { return { answer: JSON.parse(t) }; } catch (e) { return { answer: t }; }
  }
  if (!t.trim()) return { error: "type an answer first" };
  return { answer: t };
};

/**
 * What pressing button `b` of `spec` does, `other` telling whether the
 * Something else box is open and `text` its text: `{ reveal: true }` (the
 * Something else button opens its box), `{ error }` or `{ answer }` (the
 * typed box's value for a button with no `answer` of its own, else the
 * button's own; undefined for none).
 */
export const pressOf = (spec, b, other, text) => {
  if (b.other && !other) return { reveal: true };
  if (spec.input && b.outcome === "answered" && b.answer === undefined) return parseTyped(spec.input, text);
  return { answer: b.answer };
};

/**
 * Where a lineage entry says it came from, as text: a ticket (`repo#n`), then
 * any host, run and other plain fields as `key value`. A `command` is left to
 * the caller, which shows it as code; a string source is itself.
 */
export const sourceText = (src) => {
  if (typeof src === "string") return src || null;
  if (!src || typeof src !== "object") return null;
  const ticket = [src.repo, src.number != null ? `#${src.number}` : null].filter(Boolean).join("") || null;
  const rest = Object.entries(src)
    .filter(([k, v]) => !["repo", "number", "command", "session"].includes(k) && (typeof v === "string" || typeof v === "number") && String(v))
    .map(([k, v]) => `${k} ${v}`);
  return [ticket, ...rest].filter(Boolean).join(" · ") || null;
};

/** A lineage that only repeats the why (one entry whose claim is the why) says nothing more. */
export const lineageAddsNothing = (lineage, why) => lineage.length === 1 && !!why
  && typeof lineage[0].claim === "string" && lineage[0].claim.trim() === String(why).trim();

/**
 * The decision bridge for `row`: `(taskId, outcome, answer, reason)` posts
 * through `window.fleet.hitlpSurfaceDecide` with the row's `payloadDigest`, the
 * one the card shows.
 */
export const surfaceDecide = (row) => (taskId, outcome, answer, reason) => (typeof window !== "undefined" && window.fleet && window.fleet.hitlpSurfaceDecide
  ? window.fleet.hitlpSurfaceDecide(taskId, outcome, answer, reason, row.payloadDigest || null)
  : Promise.resolve({ error: "no HITLP server bridge" }));

// Most pressing first: a HITLP task's priority, then the nearest deadline.
export const PRIORITY_RANK = { urgent: 0, high: 1, normal: 2, low: 3 };
const rankOf = (r) => PRIORITY_RANK[r.priority] ?? PRIORITY_RANK.normal;
const deadlineOf = (r) => (r.deadline != null ? Number(r.deadline) : Infinity);
export const byPriority = (rows) => [...rows].sort((a, b) => rankOf(a) - rankOf(b) || deadlineOf(a) - deadlineOf(b));

/** A HITLP task's priority as a chip; `normal` draws none. */
export const PriorityChip = ({ priority }) => (priority && priority !== "normal"
  ? <span className={"nmPriority nmPriority-" + priority}>{priority}</span> : null);

/**
 * `result` is what src/needs-you.js `fetchNeeds()` returned: `{ rows, repo }`
 * (`repo` null in All repos),
 * or `{ error }` — which draws the .err block, never a
 * blank view. `restarting` (an engine update in flight, #1053) draws the error
 * as the expected restart, muted, not as a failure.
 */
export const NeedsYou = ({ result, onRefresh, refreshing, onOpenTicket, restarting = false }) => {
  const { rows: all = [], error, repo = null } = result || {};
  const rows = (Array.isArray(all) ? all : []).filter((r) => !HITLP_KINDS.includes(r.kind));
  const head = (
    <div className="head">
      <h1>Needs you{rows.length ? ` · ${rows.length}` : ""}</h1>
      <span className="meta">
        <button className={"refreshBtn" + (refreshing ? " spinning" : "")} title="Refresh now"
          disabled={refreshing} onClick={() => onRefresh && onRefresh()}>⟳</button>
      </span>
    </div>
  );
  if (error && restarting) return <div className="nmView">{head}<div className="nmEmpty restarting" title={String(error)}>{RESTARTING_VIEW}</div></div>;
  if (error) return <div className="nmView">{head}<div className="err">{String(error)}</div></div>;
  if (!rows.length) {
    return <div className="nmView">{head}
      <div className="nmEmpty">{result && result.loading ? "asking the engine…" : "Nothing is waiting on you."}</div>
    </div>;
  }
  const kinds = [...KINDS, ...new Set(rows.map((r) => r.kind).filter((k) => !KINDS.includes(k) && !HITLP_KINDS.includes(k)))]
    .filter((k) => rows.some((r) => r.kind === k));
  return (
    <div className="nmView">
      {head}
      {kinds.map((k) => (
        <div key={k}>
          <h2>{KIND_TITLES[k] || String(k)}</h2>
          {rows.filter((r) => r.kind === k).map((r) => <Row key={r.key} row={r} repo={repo} onOpenTicket={onOpenTicket} />)}
        </div>
      ))}
      <div className="foot"><span>{rows.length} waiting</span></div>
    </div>
  );
};
