// No fallback poll (#976):
// no file under src/ arms a timer (`setInterval`, or a `setTimeout` that
// re-arms itself: a function that schedules itself, or an awaited sleep in a
// loop) whose function reads an engine endpoint the stream covers. The
// inventory is the README's poll table ("The event stream", "What remains"):
// the endpoints, and the `window.fleet` methods the renderer reaches them by.
// The timers that remain (the proposals' default branch, the clocks, git,
// the app's update feed, the engine-update wait) read none of these.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const SRC = path.join(__dirname, "..", "src");

// The engine endpoints the stream covers, as they appear in a path literal.
const ENDPOINTS = [
  /["'`]\/issues["'`?]/, /\/issues\$\{/, /["'`]\/needs["'`?]/, /\/needs\$\{/, /\/intake\/\$\{/, /["'`]\/intake\/tier["'`]/,
  /\/ticket\/\$\{/, /\/camunda\/instances/, /\/camunda\/incident-fixes/, /["'`]\/accounts["'`]/, /["'`]\/repos["'`]/,
  /\/setup["'`]/, /["'`]\/repos\/setup\/check["'`]/,
];
// The bridge methods (src/preload.js) that read them, as the renderer calls them.
const BRIDGE = ["issues", "needsYou", "flow", "fetchTicket", "ticketDetail", "processInstances", "processTree", "incidentFixes",
  "accounts", "refinerTier", "repos", "setupCheck", "ticketTree", "ticketTimeline", "doneTickets"]
  .map((m) => new RegExp(`\\bfleet\\.${m}\\(`))
  // The Update engine overlay's progress (#1053) is pushed (`engine:update-progress`); its one read is on load, never on a timer.
  .concat([/\bapp\.engineUpdateProgress\(/, /engine:update-progress-state/]);
// Main-side names of the same reads (src/engine-data.js, src/ticket-tree.js, src/needs-you.js).
const MAIN_READS = ["verify", "liveRuns", "fetchIssues", "fetchNeeds", "fetchFlow", "fetchTicketDetail", "fetchProcessInstances", "fetchProcessTree",
  "fetchIncidentFixes", "fetchAccounts", "fetchRefinerTier", "fetchRepoRows", "fetchRepos", "fetchRepoSetup", "requestSetupCheck", "fetchTicketTree",
  "fetchClosedIssues", "fetchTicketTimeline", "fetchTicketData", "t.engine("]
  .map((n) => new RegExp(n.replace(/[.(]/g, "\\$&") + (n.endsWith("(") ? "" : "\\(")));

const readsEngine = (body) => ENDPOINTS.some((re) => re.test(body)) || BRIDGE.some((re) => re.test(body)) || MAIN_READS.some((re) => re.test(body));

const files = (dir, out = []) => {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) files(p, out);
    else if (/\.(js|jsx|ts)$/.test(e.name)) out.push(p);
  }
  return out;
};

// The balanced `{ … }` block that opens at `open`.
const blockAt = (src, open) => {
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === "{") depth++;
    else if (src[i] === "}" && --depth === 0) return src.slice(open, i + 1);
  }
  return src.slice(open);
};
// The body of the function `name` is bound to: `function name(` / `const name = (…) =>` / `const name = useCallback(async (…) =>` / `async name(`.
const bodyOf = (src, name) => {
  const re = new RegExp(`(?:function\\s+${name}\\s*\\(|(?:const|let|var)\\s+${name}\\s*=\\s*(?:useCallback\\()?\\s*(?:async\\s*)?(?:\\([^)]*\\)|\\w+)\\s*=>\\s*|\\b(?:async\\s+)?${name}\\s*\\([^)]*\\)\\s*\\{)`, "g");
  const bodies = [];
  let m;
  while ((m = re.exec(src))) {
    const open = src.indexOf("{", m.index + m[0].length - 1);
    if (open >= 0) bodies.push(blockAt(src, open));
  }
  return bodies.join("\n");
};
// The innermost `{ … }` function body enclosing `pos`.
const enclosing = (src, pos) => {
  let depth = 0;
  for (let i = pos; i >= 0; i--) {
    if (src[i] === "}") depth++;
    else if (src[i] === "{") { if (depth === 0) return blockAt(src, i); depth--; }
  }
  return src;
};

/** Every timer in `src`, each with the text of the function it re-arms: `{ kind, where, body }`. */
const timersOf = (src) => {
  const found = [];
  const re = /\b(setInterval|setTimeout)\s*\(\s*(?:(\w+)|(async\s*)?\(?[^)]*\)?\s*=>\s*)/g;
  let m;
  while ((m = re.exec(src))) {
    const callee = m[2];
    const inline = !callee ? enclosing(src, m.index + m[0].length) : "";
    const body = callee ? bodyOf(src, callee) : inline;
    const outer = enclosing(src, m.index);
    if (m[1] === "setInterval") found.push({ kind: "setInterval", where: m[0], body: body + "\n" + outer });
    else {
      // A setTimeout re-arms itself when the function it schedules schedules it again.
      const rearmed = callee ? new RegExp(`setTimeout\\s*\\(\\s*${callee}\\b`).test(body) : /setTimeout\s*\(/.test(inline) && /\b(for|while)\s*\(/.test(enclosing(src, m.index));
      if (rearmed) found.push({ kind: "re-armed setTimeout", where: m[0], body });
    }
  }
  // An awaited sleep inside a loop is a poll too.
  const loop = /\b(?:for\s*\(\s*;;\s*\)|while\s*\()/g;
  while ((m = loop.exec(src))) {
    const body = blockAt(src, src.indexOf("{", m.index));
    if (/await\s+(?:sleep|wait|delay)\s*\(|await new Promise\([^)]*setTimeout/.test(body)) found.push({ kind: "sleep loop", where: m[0], body: body + "\n" + enclosing(src, m.index) });
  }
  return found;
};

test("no timer under src/ reads an engine endpoint the stream covers", () => {
  const offenders = [];
  for (const file of files(SRC)) {
    const src = fs.readFileSync(file, "utf8");
    for (const t of timersOf(src)) {
      if (readsEngine(t.body)) offenders.push(`${path.relative(SRC, file)}: ${t.kind} at ${JSON.stringify(t.where.trim())}`);
    }
  }
  assert.deepEqual(offenders, [], "a fallback poll crept back in:\n" + offenders.join("\n"));
});

test("the detector sees a poll when there is one, and the deleted constants are gone", () => {
  const polling = `const load = async () => { const r = await window.fleet.issues(); setX(r); };\nuseEffect(() => { const t = setInterval(load, 1000); return () => clearInterval(t); }, []);`;
  assert.equal(timersOf(polling).filter((t) => readsEngine(t.body)).length, 1);
  const rearmed = `const poll = async () => { const d = await window.fleet.flow(id); timer = setTimeout(poll, 3000); };`;
  assert.equal(timersOf(rearmed).filter((t) => readsEngine(t.body)).length, 1);
  const sleeping = `async function run() { for (;;) { const f = await fetchFlow(id); await sleep(3000); } }`;
  assert.equal(timersOf(sleeping).filter((t) => readsEngine(t.body)).length, 1);
  const clock = `useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);`;
  assert.equal(timersOf(clock).filter((t) => readsEngine(t.body)).length, 0);
  const all = files(SRC).map((f) => fs.readFileSync(f, "utf8")).join("\n");
  for (const name of ["FLOW_REFRESH_MS", "TICKET_REFRESH_MS", "ACCOUNTS_REFRESH_MS", "NEEDS_REFRESH_MS", "PROCESS_REFRESH_MS",
    "TIMELINE_REFRESH_MS", "INCIDENT_REFRESH_MS", "ISSUES_REFRESH_MS", "TREE_REFRESH_MS", "INTAKE_POLL_MS", "TRANSCRIPT_RETRY_MS", "REPOS_REFRESH_MS",
    "fetchRepoSetups", "auto" + "Snapshot", "TRANSCRIPT_POLL_MS", "shouldSchedulePoll"]) {
    assert.ok(!all.includes(name), `${name} is still named under src/`);
  }
  assert.ok(all.includes("PROPOSALS_REFRESH_MS"), "the timer that stays");
});

// The transcript's VictoriaLogs reads (#1006): while a run is live the pane
// follows a live tail, and no timer reads VictoriaLogs. The reads it still
// makes are one-shot: the open's, a reconnect's gap read, the end's last read.
const READS_LOGS = /\b(?:fetchTranscript|postLogs|fetchEvents|readDoc|gapRead)\s*\(|select\/logsql\/query/;

test("no timer under src/ reads VictoriaLogs for the transcript, and transcriptLoop arms no timer at all", () => {
  const offenders = [];
  for (const file of files(SRC)) {
    const src = fs.readFileSync(file, "utf8");
    for (const t of timersOf(src)) {
      if (READS_LOGS.test(t.body)) offenders.push(`${path.relative(SRC, file)}: ${t.kind} at ${JSON.stringify(t.where.trim())}`);
    }
  }
  assert.deepEqual(offenders, [], "a transcript poll crept back in:\n" + offenders.join("\n"));
  const ticket = fs.readFileSync(path.join(SRC, "renderer", "ticket.jsx"), "utf8");
  const loop = bodyOf(ticket, "transcriptLoop");
  assert.ok(loop.includes("readDoc("), "found transcriptLoop's body");
  assert.doesNotMatch(loop, /setTimeout|setInterval|setTimer|pollMs/);
  // The tail client's timers are a reconnect's backoff and the end's grace period, each one-shot: no interval.
  const tail = fs.readFileSync(path.join(SRC, "transcript-tail.js"), "utf8");
  assert.doesNotMatch(tail, /setInterval/);
  // and the detector sees such a poll when there is one
  const polled = `const read = async () => { const d = await readDoc(n, cursor); timer = setTimeout(read, 4000); };`;
  assert.equal(timersOf(polled).filter((t) => READS_LOGS.test(t.body)).length, 1);
});

// The Update engine overlay (#1053): its progress reaches the renderer pushed,
// and nothing follows the update on a timer, in the renderer or in main.
test("the update overlay's progress is pushed: no timer asks for it, and the follower arms none", () => {
  const polled = `useEffect(() => { const t = setInterval(() => window.app.engineUpdateProgress().then(setP), 1000); return () => clearInterval(t); }, []);`;
  assert.equal(timersOf(polled).filter((t) => readsEngine(t.body)).length, 1, "the guard sees a poll of the new channel");
  const index = fs.readFileSync(path.join(SRC, "renderer", "index.jsx"), "utf8");
  assert.doesNotMatch(bodyOf(index, "useUpdateProgress"), /setTimeout|setInterval/);
  assert.match(bodyOf(index, "useUpdateProgress"), /onEngineUpdateProgress\(/, "it listens to the push");
  const follower = fs.readFileSync(path.join(SRC, "engine-update-progress.js"), "utf8");
  assert.doesNotMatch(follower, /setTimeout|setInterval|sleep\(/, "every step is ticked by an event, never by a timer");
  // The update overlay's file holds the Health check overlay too (#1243): its steps are ticked by `setup` frames alone.
  for (const f of ["update-overlay.jsx"]) {
    const overlay = fs.readFileSync(path.join(SRC, "renderer", f), "utf8");
    assert.doesNotMatch(overlay, /setTimeout|setInterval/, f);
  }
});
