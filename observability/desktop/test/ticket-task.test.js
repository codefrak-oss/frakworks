// A ticket's inline HITLP task (#100070): while the ticket's flow waits, the
// detail mounts hitlp-task.jsx TicketTask, which finds the task (the hint's id,
// else the open-task list by repo and ticket), reads it by id through
// window.fleet.hitlpSurfaceGet and draws the one task view (HitlpTask) with its
// decide buttons; a decided task its outcome, who and when; a surface that is
// not set, or a task that cannot be read, the surface's error in one line.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { fetchHitlpTask, fetchHitlpTasks } = require("../src/needs-you");
const { TicketTask, resolveTicketTask } = require("../dist/hitlp-task.cjs");
const { TicketDetail } = require("../dist/index.cjs");

const fixture = JSON.parse(fs.readFileSync(path.join(__dirname, "fixture-hitlp-tasks.json"), "utf8"));
// One open task the engine raised for a ticket: it names it in context.data.
const ENGINE_TASK = { ...fixture.find((t) => t.id === "task-ask-1"), id: "task-engine-1",
  context: { summary: "Which list?", data: { repo: "codefrak-oss/frakworks", ticket: 802 } } };
const tasks = [...fixture, ENGINE_TASK];
const byId = (id) => tasks.find((t) => t.id === id);
const REPO = "codefrak-oss/frakworks";
const draw = (props) => renderToStaticMarkup(React.createElement(TicketTask, { repo: REPO, number: 2201, now: 0, ...props }));
const read = (id) => fetchHitlpTask({ surface: async (x) => ({ task: byId(x) }), id });

test("fetchHitlpTask: an open task is its row; a decided one carries the decision record; not set and errors say so", async () => {
  const open = await read("task-ask-1");
  assert.equal(open.error, null);
  assert.equal(open.row.actionId, "task-ask-1");
  assert.ok(!open.row.decided);
  const done = await read("task-ask-closed");
  assert.equal(done.row.decided, true);
  assert.equal(done.row.outcome, "answered");
  assert.equal(done.row.decidedBy, "lundmatt1");
  assert.ok(Number.isInteger(done.row.decidedAt));
  const unset = await fetchHitlpTask({ configured: false, surface: async () => ({}), id: "x" });
  assert.equal(unset.notSet, true);
  assert.match(unset.error, /Config > frakworks > HITLP server/);
  const failed = await fetchHitlpTask({ surface: async () => ({ error: "HITLP server answered 404 for GET /tasks/x" }), id: "x" });
  assert.match(failed.error, /^HITLP server: HITLP server answered 404/);
});

test("a waiting ticket's task draws the one task view with its decide buttons, and no back link", async () => {
  const { row } = await read("task-ask-1");
  const html = draw({ initial: { row } });
  assert.match(html, /<div class="htView" data-task="task-ask-1">/);
  assert.match(html, /<button[^>]*data-outcome="answered"[^>]*>Postgres/);
  assert.match(html, /<button[^>]*data-outcome="answered"[^>]*>Redis/);
  assert.match(html, /Why, as smith-run-7 states it:/);
  assert.match(html, /VERIFIED/);
  assert.doesNotMatch(html, /htBack|Needs you/);
});

test("a decided task shows the outcome, who decided it and when, instead of the buttons", async () => {
  const { row } = await read("task-ask-closed");
  const html = draw({ initial: { row } });
  assert.match(html, /htView htDecided/);
  assert.match(html, /<span class="htOutcome">answered<\/span> by <span class="htDecidedBy">lundmatt1<\/span>/);
  assert.ok(html.includes(new Date(row.decidedAt * 1000).toLocaleString()), "when");
  assert.doesNotMatch(html, /data-outcome=|htBtn/);
});

test("not set, or not readable: the surface's error in one line, no buttons", async () => {
  const unset = await fetchHitlpTask({ configured: false, id: "x" });
  const html = draw({ initial: { error: unset.error } });
  assert.match(html, /^<div class="err htTicketErr">no HITLP server set: Config &gt; frakworks &gt; HITLP server<\/div>$/);
  assert.doesNotMatch(html, /<button/);
});

test("resolveTicketTask: the hint's id first, else the open task naming the ticket; none or not set is an error", async () => {
  const got = [];
  const fleet = {
    hitlpTasks: async () => fetchHitlpTasks({ surface: async () => ({ tasks }) }),
    hitlpSurfaceGet: async (id) => { got.push(id); return read(id); },
  };
  assert.equal((await resolveTicketTask({ repo: REPO, number: 1, taskId: "task-ask-2", fleet })).row.actionId, "task-ask-2");
  assert.equal((await resolveTicketTask({ repo: REPO, number: 802, fleet })).row.actionId, "task-engine-1");
  assert.deepEqual(got, ["task-ask-2", "task-engine-1"]);
  assert.match((await resolveTicketTask({ repo: REPO, number: 999999, fleet })).error, /no open HITLP task names codefrak-oss\/frakworks#999999/);
  assert.match((await resolveTicketTask({ repo: REPO, number: 4242, taskId: "task-engine-1", fleet })).error,
    /^the hint names a task of codefrak-oss\/frakworks#802, not this ticket$/);
  const unset = { hitlpTasks: async () => fetchHitlpTasks({ configured: false }), hitlpSurfaceGet: async () => ({}) };
  assert.match((await resolveTicketTask({ repo: REPO, number: 1, fleet: unset })).error, /Config > frakworks > HITLP server/);
});

test("the decision goes through hitlpSurfaceDecide, never the engine; after { ok } the task is read again", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "hitlp-task.jsx"), "utf8");
  const tt = src.slice(src.indexOf("export const TicketTask"));
  assert.match(tt, /<HitlpTask key=\{row\.actionId\} row=\{row\} now=\{now\} decide=\{decide\} onDecided=\{\(\) => setRound\(\(n\) => n \+ 1\)\} \/>/);
  assert.doesNotMatch(tt, /fleet\.(answer|decide|engine)/);
});

test("a ticket whose flow is not waiting shows no task card and no decision text", () => {
  const issue = { number: 5, repo: REPO, title: "t", labels: [], author: "a", url: "u", pendingQuestion: null };
  const html = renderToStaticMarkup(React.createElement(TicketDetail, { repo: REPO, number: 5, issue, needs: [], now: 0 }));
  assert.doesNotMatch(html, /htView|htTicketErr|reading the HITLP task|Decide/);
});
