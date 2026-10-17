// Tickets > Board view (#100094): the stage-to-column mapping, the running
// decision, and the board drawn from fixture-issues.json.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { STAGE_SEGMENTS, Issues } = require("../dist/issues.cjs");
const { StageBoard, STAGE_COLUMNS, SEGMENT_COLUMNS, stageColumnOf, isSmithRunning, groupByColumn } = require("../dist/stage-board.cjs");

const board = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "fixture-issues.json"), "utf8"));
const COLUMN_IDS = STAGE_COLUMNS.map((c) => c.id);
const row = (kind, stage, extra = {}) => ({ number: 1, repo: "o/r", title: "t", kind, flow: { flow: kind, stage, ended: false, incident: null }, ...extra });

test("every stage id of every kind maps to a column", () => {
  assert.deepEqual(Object.keys(SEGMENT_COLUMNS).sort(), Object.keys(STAGE_SEGMENTS).sort());
  for (const [kind, rows] of Object.entries(STAGE_SEGMENTS)) {
    assert.equal(SEGMENT_COLUMNS[kind].length, rows.length, kind);
    rows.forEach(([ids], i) => {
      for (const id of ids) {
        const col = stageColumnOf(row(kind, id));
        assert.ok(COLUMN_IDS.includes(col) && col !== "waiting", `${kind} ${id} -> ${col}`);
        // a stage id listed twice in a kind lands in its first row's column
        const first = rows.findIndex(([ids2]) => ids2.includes(id));
        assert.equal(col, SEGMENT_COLUMNS[kind][first], `${kind} ${id}`);
      }
    });
  }
  assert.equal(stageColumnOf(row("feature", "call-design")), "designing");
  assert.equal(stageColumnOf(row("chore", "call-ci-check")), "building");
  assert.equal(stageColumnOf(row("bug", "call-reproduce")), "intake");
  assert.equal(stageColumnOf(row("chore", "call-review-pr")), "reviewing");
  assert.equal(stageColumnOf(row("feature", "task-merge-pr")), "merging");
});

test("no flow, an ended flow or an unknown stage lands in Waiting", () => {
  assert.equal(stageColumnOf({ kind: "chore" }), "waiting");
  assert.equal(stageColumnOf(row("chore", "call-do-work", { flow: { flow: "chore", stage: "call-do-work", ended: true } })), "waiting");
  assert.equal(stageColumnOf(row("chore", "no-such-stage")), "waiting");
  assert.equal(stageColumnOf(row("mystery", "call-do-work")), "waiting");
});

test("a Smith runs when the flow is live and not holding", () => {
  assert.equal(isSmithRunning(row("chore", "call-do-work")), true);
  assert.equal(isSmithRunning(row("feature", "user-task-answer-question")), false);
  assert.equal(isSmithRunning(row("chore", "call-do-work", { pendingQuestion: { url: "x" } })), false);
  assert.equal(isSmithRunning({ ...row("chore", "call-do-work"), flow: { flow: "chore", stage: "call-do-work", incident: { id: "i" } } }), false);
  assert.equal(isSmithRunning({ ...row("chore", "call-do-work"), flow: { flow: "chore", stage: "call-do-work", ended: true } }), false);
  assert.equal(isSmithRunning({ kind: "chore" }), false);
});

test("the board draws a column per stage from the fixture: a running card with an estimate shows its bar, a holding one does not", () => {
  const html = renderToStaticMarkup(React.createElement(StageBoard, { issues: board.issues, onOpenTicket: () => {} }));
  for (const c of STAGE_COLUMNS) assert.match(html, new RegExp(`sbCol sb-${c.id}`));
  const cards = html.split('<a class="sbCard').slice(1);
  const card = (n) => cards.find((c) => c.includes(`>#${n}<`));
  assert.match(card(64), /^ sbRunning/); assert.match(card(64), /role="progressbar"/); assert.doesNotMatch(card(64), /sbSweep/);
  assert.doesNotMatch(card(60), /sbSweep/); assert.match(card(60), /sbHold/);
  assert.doesNotMatch(card(65), /sbSweep/);
  const cols = groupByColumn(board.issues);
  assert.equal(cols.reduce((n, c) => n + c.rows.length, 0), board.issues.length);
  assert.ok(cols.find((c) => c.id === "coding").rows.some((r) => r.number === 64));
});

test("a row update moves a card and a closed row's card goes", () => {
  const r = row("chore", "call-do-work", { number: 7 });
  const col = (rows) => groupByColumn(rows).find((c) => c.rows.some((x) => x.number === 7));
  assert.equal(col([r]).id, "coding");
  assert.equal(col([{ ...r, flow: { ...r.flow, stage: "call-review-pr" } }]).id, "reviewing");
  assert.equal(col([]), undefined);
});

test("the Tickets pane shows the board under the Board toggle", () => {
  const html = renderToStaticMarkup(React.createElement(Issues, { result: board, onOpenTicket: () => {}, view: "board" }));
  assert.match(html, /sbBoard/); assert.match(html, /checked="" value="board"/);
  const table = renderToStaticMarkup(React.createElement(Issues, { result: board, onOpenTicket: () => {} }));
  assert.doesNotMatch(table, /sbBoard/); assert.match(table, /<table/);
});

test("a running card shows the progress bar with an estimate, the sweep without, neither when not running (#100111)", () => {
  const at = Date.parse("2026-10-17T12:00:00Z");
  const flow = { flow: "chore", stage: "call-do-work", ended: false, incident: null, outcome: "started", instance: "i" };
  const draw = (r) => renderToStaticMarkup(React.createElement(StageBoard, { issues: [r], now: at }));
  const est = draw(row("chore", "call-do-work", { flow: { ...flow, stageStartedAt: "2026-10-17T11:50:00Z", stageEstimateMs: 1200000 } }));
  assert.match(est, /sbRunning/);
  assert.match(est, /role="progressbar"[^>]*aria-valuenow="\d+"/);
  assert.doesNotMatch(est, /sbSweep/);
  const none = draw(row("chore", "call-do-work", { flow }));
  assert.match(none, /sbRunning/);
  assert.match(none, /sbSweep/);
  assert.doesNotMatch(none, /progressbar/);
  // the open step's bar, not the whole ticket's: 10 of 20 minutes is 50%
  assert.match(est, /aria-valuenow="50"/);
  assert.match(est, /est\. 20m/);
  const late = draw(row("chore", "call-do-work", { flow: { ...flow, stageStartedAt: "2026-10-17T11:00:00Z", stageEstimateMs: 1200000 } }));
  assert.match(late, /isProgressStep sbProgress hold/);
  const waits = draw(row("chore", "user-task-answer", { flow: { ...flow, stage: "user-task-answer", stageStartedAt: "2026-10-17T11:50:00Z", stageEstimateMs: 1200000 } }));
  if (/sbRunning/.test(waits)) assert.doesNotMatch(waits, /progressbar/);
  const idle = draw(row("chore", "call-do-work", { flow: { ...flow, ended: true, stageEstimateMs: 1200000, stageStartedAt: "2026-10-17T11:50:00Z" } }));
  assert.doesNotMatch(idle, /sbSweep|progressbar/);
});
