// Config > Jobs (#1228): src/renderer/jobs.jsx drawing GET /jobs?repo=, the
// `job` frames through the renderer's event path, the Register form's body and
// its inline errors, Run now and Delete against a fake main, and
// src/engine-data.js's four calls against a stand-in fetch.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { JobsView, InstanceLink, jobMenuPos, jobBody, useJobRuns, registerJob, runJobNow, deleteJobRow, EMPTY_FORM, signalText, shortTime, durationText } = require("../dist/jobs.cjs");
const { fetchJobs, registerJob: postJob, executeJob, deleteJob } = require("../src/engine-data");

const JOBS = require("./fixture-jobs.json");
const REPO = "acme/widgets";
// The wide layout's text: the narrower layouts' alternates (class jbMid or jbNarrow) dropped.
const wideOnly = (html) => {
  let out = "";
  for (let i = 0; i < html.length;) {
    const m = /^<(\w+)[^>]*class="[^"]*\bjb(?:Mid|Narrow)\b[^"]*"[^>]*>/.exec(html.slice(i));
    if (!m) { out += html[i++]; continue; }
    let depth = 0;
    const re = new RegExp(`<(/?)${m[1]}\\b[^>]*>`, "g");
    re.lastIndex = i;
    for (let t; (t = re.exec(html));) { depth += t[1] ? -1 : 1; if (!depth) { i = re.lastIndex; break; } }
  }
  return out;
};
const cells = (html, id) => {
  const row = wideOnly(html).match(new RegExp(`<tr data-job="${id}">([\\s\\S]*?)</tr>`))[1];
  return [...row.matchAll(/<td[^>]*>([\s\S]*?)<\/td>/g)].map((m) => m[1].replace(/<[^>]+>/g, ""));
};

test("the list: one row per job with name, kind, signal, next run, last run and the instance link; no state column", () => {
  const html = renderToStaticMarkup(React.createElement(JobsView, { repo: REPO, result: { jobs: JOBS }, form: EMPTY_FORM }));
  const head = wideOnly(html).match(/<thead>([\s\S]*?)<\/thead>/)[1];
  assert.deepEqual([...head.matchAll(/<th[^>]*>(.*?)<\/th>|<th[^>]*\/>/g)].map((m) => (m[1] || "").replace(/<[^>]+>/g, "")),
    ["name", "kind", "signal", "next run", "last run", "outcome", "instance", ""]);
  assert.deepEqual(cells(html, "j-prompt").slice(0, 7),
    ["nightly digest", "prompt", "every 1 h", shortTime("2026-10-06T11:00:00Z"), shortTime("2026-10-06T10:00:00Z"), "—", "inst-1"]);
  assert.deepEqual(cells(html, "j-bean").slice(0, 7),
    ["label sweep", "bean", "on demand", "—", shortTime("2026-10-05T09:00:00Z"), "—", "inst-2"]);
  assert.deepEqual(cells(html, "j-flow").slice(0, 7), ["release train", "flow", "on demand", "—", "—", "—", "—"]);
  assert.match(html, /data-instance="inst-1"/);
  assert.match(html, /data-instance="inst-2"/);
  assert.equal((html.match(/data-instance=/g) || []).length, 4, "no link for lastInstance: null (each link twice: its column and folded into outcome)");
});

// The hook harness of test/engine-events-renderer.test.js: React's hooks stubbed, effects run after a render.
const harness = () => {
  const real = { useRef: React.useRef, useState: React.useState, useEffect: React.useEffect };
  const slots = [];
  let cleanups = [];
  const run = (fn) => {
    let idx = 0;
    const effects = [];
    React.useRef = (v) => { const c = (slots[idx] ||= { current: v }); idx++; return c; };
    React.useState = (v) => { const c = (slots[idx] ||= { s: v }); idx++; return [c.s, (n) => { c.s = typeof n === "function" ? n(c.s) : n; }]; };
    React.useEffect = (f) => { effects.push(f); };
    let out;
    try { out = fn(); } finally { Object.assign(React, real); }
    for (const c of cleanups) c();
    cleanups = [];
    for (const f of effects) { const c = f(); if (typeof c === "function") cleanups.push(c); }
    return out;
  };
  return { run };
};

test("job frames: started marks the row running with its instance, ended shows done or failed; another repo or job changes nothing", () => {
  const listeners = new Set();
  global.window = { fleet: { onEngineEvent: (cb) => { listeners.add(cb); return () => listeners.delete(cb); } } };
  const frame = (data) => { for (const cb of [...listeners]) cb({ type: "job", data: { name: "n", kind: "prompt", ...data }, subscription: REPO }); };
  const h = harness();
  const runs = () => h.run(() => useJobRuns(REPO, JOBS))[0];
  runs();
  frame({ repo: REPO, jobId: "j-flow", instance: "inst-9", state: "started" });
  let r = runs();
  assert.deepEqual(r["j-flow"], { running: true, outcome: null, instance: "inst-9" });
  let html = renderToStaticMarkup(React.createElement(JobsView, { repo: REPO, result: { jobs: JOBS }, runs: r, form: EMPTY_FORM }));
  assert.deepEqual(cells(html, "j-flow").slice(5, 7), ["running", "inst-9"]);
  frame({ repo: REPO, jobId: "j-flow", instance: "inst-9", state: "ended", outcome: "failed" });
  r = runs();
  html = renderToStaticMarkup(React.createElement(JobsView, { repo: REPO, result: { jobs: JOBS }, runs: r, form: EMPTY_FORM }));
  assert.deepEqual(cells(html, "j-flow").slice(5, 7), ["failed", "inst-9"]);
  frame({ repo: REPO, jobId: "j-prompt", instance: "inst-10", state: "ended", outcome: "done" });
  r = runs();
  html = renderToStaticMarkup(React.createElement(JobsView, { repo: REPO, result: { jobs: JOBS }, runs: r, form: EMPTY_FORM }));
  assert.deepEqual(cells(html, "j-prompt").slice(5, 7), ["done", "inst-10"]);
  const before = JSON.stringify(r);
  frame({ repo: "other/repo", jobId: "j-bean", instance: "x", state: "started" });
  frame({ repo: REPO, jobId: "j-unknown", instance: "y", state: "started" });
  assert.equal(JSON.stringify(runs()), before);
  delete global.window;
});

test("the Register form's body: exactly name, kind, repo, config (the kind's keys) and signal, for each kind and both signals", () => {
  const all = { name: "x", prompt: "p", jar: "a.jar", bean: "b", process: "pr" };
  const cases = [
    ["prompt", { prompt: "p" }], ["bean", { jar: "a.jar", bean: "b" }], ["flow", { jar: "a.jar", process: "pr" }],
  ];
  for (const [kind, config] of cases) {
    assert.deepEqual(jobBody({ ...all, kind, signal: "every", every: "90" }, REPO).body,
      { name: "x", kind, repo: REPO, config, signal: { kind: "every", every: 90 } });
    const b = jobBody({ ...all, kind, signal: "execute", every: "" }, REPO).body;
    assert.deepEqual(b, { name: "x", kind, repo: REPO, config, signal: { kind: "execute" } });
    assert.deepEqual(Object.keys(b), ["name", "kind", "repo", "config", "signal"]);
  }
  assert.ok(jobBody({ ...all, kind: "prompt", signal: "every", every: "1.5" }, REPO).error, "seconds are a whole number");
  assert.ok(jobBody({ ...all, kind: "prompt", signal: "execute" }, null).error, "no repo, nothing sent");
  assert.equal(signalText({ kind: "every", every: 5 }), "every 5 s");
});

test("times and signals read humanly: short local time, whole units, never raw ISO", () => {
  assert.equal(shortTime("2026-10-08T00:48:29.352401Z", "UTC"), "Oct 8, 00:48");
  assert.equal(shortTime(null), "—");
  assert.equal(shortTime("nope"), "—");
  assert.equal(durationText(3600), "1 h");
  assert.equal(durationText(1800), "30 min");
  assert.equal(durationText(86400), "1 d");
  assert.equal(durationText(90), "90 s");
  assert.equal(signalText({ kind: "every", every: 3600 }), "every 1 h");
  assert.equal(signalText('{"kind":"every","every":1800}'), "every 30 min");
  assert.equal(signalText({ kind: "at", at: "2026-10-06T18:00:00Z" }, "UTC"), "at Oct 6, 18:00");
  assert.equal(signalText({ kind: "execute" }), "on demand");
});

const fakeMain = (answers) => {
  const calls = [];
  let list = JOBS.slice();
  return { calls, fleet: {
    jobs: async (repo) => { calls.push(["jobs", repo]); return { jobs: list }; },
    jobRegister: async (body) => { calls.push(["register", body]); const a = answers.register; if (a.ok) list = [...list, { ...body, id: "j-new" }]; return a; },
    jobExecute: async (id) => { calls.push(["execute", id]); return answers.execute; },
    jobDelete: async (id) => { calls.push(["delete", id]); return answers.delete; },
  } };
};
const FORM = { ...EMPTY_FORM, name: "new one", kind: "bean", signal: "execute", jar: "x.jar", bean: "b" };

test("Register: a 400's two lines inline and verbatim with the form kept; a 403's message; a 201 clears the form and adds the row", async () => {
  const body = "config.jar is a file name in /var/lib/frakworks/jobs, not a path\nsignal.every must be at least 1";
  let m = fakeMain({ register: { error: "engine answered 400 for POST /jobs: …", status: 400, body } });
  let r = await registerJob(m.fleet, FORM, REPO);
  assert.equal(r.form, FORM);
  assert.equal(r.open, true, "a failure keeps the modal open");
  let html = renderToStaticMarkup(React.createElement(JobsView, { repo: REPO, result: { jobs: JOBS }, form: r.form, formError: r.error, registerOpen: r.open }));
  assert.match(html, /<div>config\.jar is a file name in \/var\/lib\/frakworks\/jobs, not a path<\/div><div>signal\.every must be at least 1<\/div>/);
  assert.match(html, /value="new one"/);
  assert.match(html, /value="x\.jar"/);
  m = fakeMain({ register: { error: "engine answered 403 …", status: 403, body: "a Smith sandbox (run r-1) may not register a job" } });
  r = await registerJob(m.fleet, FORM, REPO);
  html = renderToStaticMarkup(React.createElement(JobsView, { repo: REPO, result: { jobs: JOBS }, form: r.form, formError: r.error, registerOpen: r.open }));
  assert.match(html, /a Smith sandbox \(run r-1\) may not register a job/);
  m = fakeMain({ register: { ok: true, status: 201 } });
  r = await registerJob(m.fleet, FORM, REPO);
  assert.deepEqual(r.form, EMPTY_FORM);
  assert.equal(r.error, null);
  assert.equal(r.open, false, "a 201 closes the modal");
  html = renderToStaticMarkup(React.createElement(JobsView, { repo: REPO, result: { jobs: r.jobs }, form: r.form }));
  assert.match(html, /data-job="j-new"/);
  assert.deepEqual(m.calls.map((c) => c[0]), ["register", "jobs"]);
});

test("Run now: the instance becomes the row's link; a 409 shows inline on that row", async () => {
  let m = fakeMain({ execute: { ok: true, instance: "inst-77" } });
  const r = await runJobNow(m.fleet, JOBS[2]);
  assert.deepEqual(m.calls, [["execute", "j-flow"]]);
  let html = renderToStaticMarkup(React.createElement(JobsView, { repo: REPO, result: { jobs: JOBS }, runs: { "j-flow": { instance: r.instance } }, form: EMPTY_FORM }));
  assert.match(cells(html, "j-flow")[6], /inst-77/);
  assert.match(html, /data-instance="inst-77"/);
  m = fakeMain({ execute: { error: "engine answered 409", status: 409, body: "already running" } });
  const e = await runJobNow(m.fleet, JOBS[2]);
  html = renderToStaticMarkup(React.createElement(JobsView, { repo: REPO, result: { jobs: JOBS }, rowErrors: { "j-flow": e.error }, form: EMPTY_FORM }));
  assert.match(html, /<tr data-job="j-flow">[\s\S]*?<\/tr><tr><td colSpan="8"><div class="err jbErr"><div>already running<\/div>/);
});

test("Delete: a confirm naming the job, DELETE only on confirm, removed on 204, a 409 or 403 inline", async () => {
  let m = fakeMain({ delete: { ok: true, status: 204 } });
  const asked = [];
  assert.deepEqual(await deleteJobRow(m.fleet, JOBS[0], (q) => { asked.push(q); return false; }), { cancelled: true });
  assert.match(asked[0], /"nightly digest"/);
  assert.deepEqual(m.calls, []);
  assert.deepEqual(await deleteJobRow(m.fleet, JOBS[0], () => true), { removed: true });
  assert.deepEqual(m.calls, [["delete", "j-prompt"]]);
  for (const [status, body] of [[409, "job j-prompt is running in inst-1"], [403, "a Smith sandbox may not execute a job"]]) {
    m = fakeMain({ delete: { error: `engine answered ${status}`, status, body } });
    const r = await deleteJobRow(m.fleet, JOBS[0], () => true);
    assert.equal(r.error, body);
  }
});

test("engine-data: GET /jobs needs a repo; the writes carry no X-Frakworks-Login and keep the engine's body", async () => {
  const env = { ENGINE_URL: "http://engine.test:8090" };
  const seen = [];
  const fetch = async (url, init) => {
    seen.push({ url, method: (init && init.method) || "GET", headers: (init && init.headers) || {}, body: init && init.body });
    if (url.endsWith("/execute")) return { ok: false, status: 409, text: async () => "already running" };
    if (init && init.method === "DELETE") return { ok: true, status: 204, text: async () => "" };
    if (init && init.method === "POST") return { ok: true, status: 201, text: async () => JSON.stringify({ id: "j" }) };
    return { ok: true, status: 200, text: async () => "[]" };
  };
  assert.ok((await fetchJobs(undefined, { env, fetch })).error);
  assert.equal(seen.length, 0, "a missing repo is not sent");
  assert.deepEqual(await fetchJobs(REPO, { env, fetch }), { jobs: [] });
  assert.equal(seen[0].url, "http://engine.test:8090/jobs?repo=acme%2Fwidgets");
  const job = { name: "n", kind: "prompt", repo: REPO, config: { prompt: "p" }, signal: { kind: "execute" } };
  assert.equal((await postJob(job, "lundmatt1", { env, fetch })).ok, true);
  assert.deepEqual(JSON.parse(seen[1].body), job);
  const ex = await executeJob("j-1", "lundmatt1", { env, fetch });
  assert.equal(ex.status, 409);
  assert.equal(ex.body, "already running");
  assert.equal((await deleteJob("j-1", "lundmatt1", { env, fetch })).ok, true);
  assert.deepEqual(seen.slice(1).map((s) => [s.method, s.url.replace(env.ENGINE_URL, ""), s.headers["X-Frakworks-Login"]]),
    [["POST", "/jobs", undefined], ["POST", "/jobs/j-1/execute", undefined], ["DELETE", "/jobs/j-1", undefined]]);
  assert.ok((await deleteJob("j-1", null, { env, fetch })).error, "no login, nothing sent");
  assert.equal(seen.length, 4);
});

test("no poll of /jobs: jobs.jsx has no timer", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "jobs.jsx"), "utf8");
  assert.doesNotMatch(src, /setInterval|setTimeout/);
});

test("a system job (owner system) shows a System badge and Run now, never Delete; a user job keeps Delete", () => {
  const html = renderToStaticMarkup(React.createElement(JobsView, { repo: REPO, result: { jobs: JOBS }, form: EMPTY_FORM }));
  const row = (id) => html.match(new RegExp(`<tr data-job="${id}">([\\s\\S]*?)</tr>`))[1];
  assert.match(row("j-refactor"), /class="jbSystem"[^>]*>System</);
  assert.match(row("j-refactor"), />Run now</);
  assert.doesNotMatch(row("j-refactor"), />Delete</);
  assert.doesNotMatch(row("j-prompt"), /jbSystem/);
  assert.match(row("j-prompt"), />Run now</);
  assert.match(row("j-prompt"), />Delete</);
});

test("Register modal: closed, a button and no form; open, the form in the app's dialog with Cancel; a click outside closes, inside does not", () => {
  let html = renderToStaticMarkup(React.createElement(JobsView, { repo: REPO, result: { jobs: JOBS }, form: EMPTY_FORM }));
  assert.match(html, />Register a job<\/button>/);
  assert.doesNotMatch(html, /jbForm|role="dialog"/);
  html = renderToStaticMarkup(React.createElement(JobsView, { repo: REPO, result: { jobs: JOBS }, form: EMPTY_FORM, registerOpen: true, onCloseRegister: () => {} }));
  assert.match(html, /class="uoBackdrop" role="dialog" aria-modal="true" aria-label="Register a job"/);
  assert.match(html, /class="uoPanel jbPanel"><form class="jbForm"/);
  assert.match(html, />Cancel<\/button>/);
  html = renderToStaticMarkup(React.createElement(JobsView, { repo: null, result: {}, form: EMPTY_FORM, registerOpen: true }));
  assert.doesNotMatch(html, /Register a job|role="dialog"/);
  const { backdropClick } = require("../dist/modal-overlay.cjs");
  let closed = 0;
  const scrim = {};
  backdropClick({ target: scrim, currentTarget: scrim }, () => closed++);
  backdropClick({ target: {}, currentTarget: scrim }, () => closed++);
  assert.equal(closed, 1);
});

test("narrow layouts: an 8-character instance link with the full id, a kind tag, one when cell, instance in outcome and a ⋯ menu", () => {
  const id = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
  const opened = [];
  const html = renderToStaticMarkup(React.createElement(JobsView, { repo: REPO, result: { jobs: JOBS }, runs: { "j-flow": { instance: id } }, form: EMPTY_FORM }));
  const row = (id) => html.match(new RegExp(`<tr data-job="${id}">([\\s\\S]*?)</tr>`))[1];
  assert.match(cells(html, "j-flow")[6], /^0f1e2d3c$/);
  assert.equal((row("j-flow").match(new RegExp(`data-instance="${id}" title="open process instance ${id}"`, "g")) || []).length, 2, "column and folded link");
  assert.match(row("j-flow"), /class="jbNarrow"> <button class="pvLink"/);
  assert.match(row("j-prompt"), /class="jbKindTag jbMid">prompt</);
  assert.match(row("j-prompt"), new RegExp(`class="jbMid jbWhen"><span>next <span[^>]*>${shortTime("2026-10-06T11:00:00Z")}</span></span><span>last <span[^>]*>${shortTime("2026-10-06T10:00:00Z")}</span>`));
  const menu = (id) => row(id).match(/<details class="jbMore jbNarrow">([\s\S]*?)<\/details>/)[1];
  assert.match(menu("j-prompt"), />⋯<\/summary>[\s\S]*>Run now<[\s\S]*>Delete</);
  assert.doesNotMatch(menu("j-refactor"), />Delete</);
  InstanceLink({ id, onOpen: (x) => opened.push(x) }).props.onClick();
  assert.deepEqual(opened, [id], "the short link opens the full id");
});

test("styles: the jobs view is an inline-size container with the 900px and 620px @container rules", () => {
  const css = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "styles.css"), "utf8");
  assert.match(css, /\.fleet-root\.jobs \{ container-type: inline-size; \}/);
  assert.match(css, /@container \(max-width: 900px\)/);
  assert.match(css, /@container \(max-width: 620px\)/);
});

test("the ⋯ menu escapes .jbScroll's clip: fixed, placed from the summary's rect (#1758)", () => {
  const css = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "styles.css"), "utf8");
  assert.match(css, /\.fleet-root\.jobs \.jbScroll \{[^}]*overflow-x: auto/);
  assert.match(css, /\.fleet-root\.jobs \.jbMenu \{ position: fixed;/);
  assert.doesNotMatch(css, /\.fleet-root\.jobs \.jbMore \{[^}]*position: relative/);
  // #1777: the containing block is the viewport, not the jobs pane. The pane
  // here spans x 200..800 from top 100 in a 1200px window (First Mate to its right).
  assert.deepEqual(jobMenuPos({ top: 300, bottom: 320, right: 780 }, 1200, 60, 800), { top: 322, right: 420 });
  assert.ok(1200 - 420 <= 800, "the menu's right edge stays inside the jobs pane");
  assert.deepEqual(jobMenuPos({ top: 760, bottom: 780, right: 780 }, 1200, 60, 800), { top: 698, right: 420 }, "flips above near the window's bottom");
  assert.doesNotMatch(css, /container-type below makes/);
  const html = renderToStaticMarkup(React.createElement(JobsView, { repo: REPO, result: { jobs: JOBS }, form: EMPTY_FORM }));
  assert.match(html, /<details class="jbMore jbNarrow"><summary/);
});

test("the ⋯ menu is narrow-only: no inline-block rule for .jbMore outside the 620px block", () => {
  const css = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "styles.css"), "utf8");
  const narrowBlock = css.match(/@container \(max-width: 620px\) \{([\s\S]*?)\n\}/)[1];
  const outsideNarrowBlock = css.slice(0, css.indexOf(narrowBlock)) + css.slice(css.indexOf(narrowBlock) + narrowBlock.length);
  assert.doesNotMatch(outsideNarrowBlock, /\.fleet-root\.jobs \.jbMore\s*\{[^}]*display:\s*inline-block[^}]*\}/, "the ⋯ menu must not be shown unconditionally");
  assert.match(narrowBlock, /\.fleet-root\.jobs (?:details\.jbMore|\.jbMore)\s*\{[^}]*display:\s*inline-block[^}]*\}/, "the ⋯ menu is shown inside the 620px block");
  assert.match(narrowBlock, /\.fleet-root\.jobs \.jbNarrow \{[^}]*display:\s*inline[^}]*\}/, "jbNarrow elements are shown inside the 620px block");
});
