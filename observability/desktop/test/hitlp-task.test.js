// The HITLP task view: src/hitlp-task.js (headline, script, odds, labels) and
// the view src/renderer/hitlp-task.jsx draws: decision first, the story below.
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const T = require("../src/hitlp-task.js");
const { HitlpTask } = require("../dist/hitlp-task.cjs");

const NOW = 1_800_000_000;
const SCRIPT = ["set -euo pipefail", "DIR=/var/log/app", "for f in $(find \"$DIR\" -name '*.log' -mtime +30); do", "  echo \"$f\"",
  "done", "du -sh \"$DIR\"", "find \"$DIR\" -name '*.log.gz'", "date", "uptime", "df -h"].join("\n");
const approve = (over = {}) => ({
  kind: "approve", actionId: "t-1", action: "run", priority: "high", deadline: NOW + 52 * 60, since: NOW - 8 * 60,
  attested: { requester: "ops-laptop" },
  why: "Prune old log files under /var/log.",
  payload: { tool: "run", host: "host-a", user: "root", command: SCRIPT },
  payloadDigest: "sha256:abc", scope: { maxUses: 1 },
  recommendation: { verdict: "escalate", confidence: 0.71, by: { requester: "ops-server", judge: "in-process judge" },
    reasons: ["", "Jev jev-1.13.0: allow 0.22, escalate 0.71, deny 0.07"] },
  lineage: [
    { kind: "command", claim: "Prune old log files under /var/log.", by: "ops-laptop", source: { host: "host-a", command: "rm -rf …" } },
    { kind: "run", claim: "Found 380 files older than 30 days", by: "smith run 7" },
    { kind: "ticket", claim: "host-a's disk is at 91%", by: "lundmatt1", source: { repo: "lundmatt1/example", number: 81 } },
  ],
  ...over,
});
const draw = (row, props = {}) => renderToStaticMarkup(React.createElement(HitlpTask, { row, now: NOW, teachReady: false, ...props }));

test("headlineOf: a short one-line command inline, a script by its length, else the action or the question", () => {
  assert.deepEqual(T.headlineOf(approve({ payload: { host: "host-a", user: "root", command: "uptime" } })), { text: "Run", code: "uptime", where: "on host-a as root" });
  assert.deepEqual(T.headlineOf(approve()), { text: "Run a 10-line script on host-a as root", code: null });
  assert.equal(T.headlineOf(approve({ payload: { host: "host-a", command: "x".repeat(T.INLINE_MAX + 1) } })).text, "Run a command on host-a");
  assert.equal(T.headlineOf(approve({ payload: { note: "no command" }, action: "deploy v2" })).text, "deploy v2");
  assert.equal(T.headlineOf({ kind: "ask", question: "Which store?" }).text, "Which store?");
});

test("scriptOf counts lines and characters, without a trailing newline; null with no command", () => {
  const s = T.scriptOf(approve({ payload: { command: "a\nb\n\n", host: "h" } }));
  assert.equal(s.lines, 2); assert.equal(s.chars, 3); assert.equal(s.host, "h"); assert.equal(s.shell, "bash");
  assert.equal(T.scriptOf({ payload: {} }), null);
  assert.equal(T.scriptOf(null), null);
});

test("oddsOf reads probabilities, else the ops server's reason line; leanOf picks the largest", () => {
  assert.deepEqual(T.oddsOf(approve().recommendation), { allow: 0.22, escalate: 0.71, deny: 0.07 });
  assert.deepEqual(T.oddsOf({ probabilities: { allow: 0.9, deny: 0.1 } }), { allow: 0.9, escalate: 0, deny: 0.1 });
  assert.equal(T.oddsOf({ verdict: "allow" }), null);
  assert.equal(T.leanOf({ allow: 0.22, escalate: 0.71, deny: 0.07 }), "escalate");
});

test("initialsOf, leftShare", () => {
  assert.equal(T.initialsOf("ops-laptop"), "OL");
  assert.equal(T.initialsOf("lundmatt1"), "LU");
  assert.equal(T.initialsOf("smith run 7"), "SR");
  assert.equal(T.initialsOf(null), "?");
  assert.equal(T.leftShare(approve(), NOW), 52 / 60);
  assert.equal(T.leftShare({ deadline: null }, NOW), null);
});

test("the view: decision first (priority, verified sender, headline, why, script, odds, buttons), the story below ending in this request", () => {
  const html = draw(approve());
  const at = (re) => { const m = html.search(re); assert.ok(m >= 0, `missing ${re}`); return m; };
  assert.match(html, /class="htPriority high">HIGH</);
  assert.match(html, /FROM ops-laptop <span class="htVerified">.*VERIFIED<\/span>/);
  assert.match(html, /<h1 class="htHeadline">Run a 10-line script on host-a as root<\/h1>/);
  assert.match(html, /Why, as ops-laptop states it:<\/span> Prune old log files/);
  assert.match(html, /root@host-a · bash/);
  assert.match(html, /10 lines · \d+ characters/);
  assert.match(html, />Expand</, "a long script can be expanded");
  assert.match(html, /leans <strong class="htLean escalate">escalate<\/strong>/);
  assert.match(html, /data-outcome="approved" class="htBtn approve">Approve<kbd>A<\/kbd>/);
  assert.match(html, /data-outcome="rejected" class="htBtn reject">Reject<kbd>R<\/kbd>/);
  // The story: first step first, each reported and not verified, then this request, verified.
  const ticket = at(/host-a&#x27;s disk is at 91%/);
  const run = at(/Found 380 files/);
  const self = at(/class="htStep self"/);
  assert.ok(at(/class="htDecide"/) < ticket && ticket < run && run < self, "decide, then the story in order, then this request");
  assert.match(html, /lundmatt1\/example#81/);
  assert.match(html, /reported by ops-laptop, not verified/);
  assert.match(html, /This request: decide at the top\./);
  assert.match(html, /Technical details: digest, scope, payload/);
});

test("a short command reads inline with no script box; a lineage that only repeats the why draws no story", () => {
  const row = approve({ payload: { host: "host-a", user: "root", command: "uptime" },
    lineage: [{ kind: "command", claim: "Prune old log files under /var/log.", by: "ops-laptop" }] });
  const html = draw(row);
  assert.match(html, /<h1 class="htHeadline">Run <code class="htInline">uptime<\/code> on host-a as root<\/h1>/);
  assert.doesNotMatch(html, /htScriptBar/);
  assert.doesNotMatch(html, /HOW THIS REACHED YOU/);
});

test("an ask: the question as the headline, its buttons, a comment box; an error draws", () => {
  const html = draw({ kind: "ask", actionId: "q-1", question: "Which store?", responseSchema: { type: "string", enum: ["pg", "redis"] },
    attested: { requester: "smith-7" }, deadline: NOW + 600, since: NOW - 60, lineage: [] }, { initialError: "already decided" });
  assert.match(html, /QUESTION · FROM smith-7/);
  assert.match(html, /<h1 class="htHeadline">Which store\?<\/h1>/);
  assert.match(html, /data-outcome="answered" class="htBtn">pg<\/button>/);
  assert.match(html, /Comment \(optional, sent with your answer\)/);
  assert.match(html, /class="err htErr">already decided</);
});

test("a number answer is a text box (a number box drops letters without a word); a non-number is refused on Answer", () => {
  const html = draw({ kind: "ask", actionId: "n-1", question: "How many days?", responseSchema: { type: "integer" },
    attested: { requester: "claude-code" }, deadline: NOW + 600, since: NOW - 60, lineage: [] });
  assert.match(html, /<input class="htAnswer" aria-label="Your answer" type="text" inputMode="decimal"[^>]*placeholder="a number"/);
  assert.doesNotMatch(html, /type="number"/);
  const { parseTyped } = require("../dist/needs-you.cjs");
  assert.deepEqual(parseTyped("number", "seven"), { error: "the answer must be a number" });
  assert.deepEqual(parseTyped("number", "7"), { answer: 7 });
});

test("a long question: the first line, or its first sentence, is the headline and the rest goes below", () => {
  assert.deepEqual(T.splitQuestion("Which store?\n\nPostgres is in the stack."), { text: "Which store?", code: null, rest: "Postgres is in the stack." });
  const long = "The `ops-k8s` MCP server isn't attached to this Smith run. I have no `open_session` or `run` tool, so I can't open a host-a session. Could you attach it?";
  const h = T.splitQuestion(long);
  assert.equal(h.text, "The `ops-k8s` MCP server isn't attached to this Smith run.");
  assert.match(h.rest, /^I have no `open_session`/);
  assert.deepEqual(T.splitQuestion("Short?"), { text: "Short?", code: null, rest: null });
  const html = draw({ kind: "ask", actionId: "q-2", question: long, responseSchema: { type: "string" }, attested: { requester: "frakworks-engine" }, lineage: [] });
  assert.match(html, /<h1 class="htHeadline">The `ops-k8s` MCP server isn&#x27;t attached to this Smith run\.<\/h1><p class="htQuestionMore">I have no/);
});

const ask = (question) => ({ kind: "ask", actionId: "a-1", action: "ask", priority: "normal", since: NOW - 60,
  attested: { requester: "smith" }, question, why: "Pick one:\n\n- **A**\n- B" });
const passThrough = (h) => h;

test("an ask's question body and why render from markdown, inline SVG kept", () => {
  const svg = '<svg xmlns="http://www.w3.org/2000/svg"><rect width="4" height="4"/></svg>';
  const html = renderToStaticMarkup(React.createElement(HitlpTask,
    { row: ask(`Pick a style?\n${svg}\n\n- one\n- two`), now: NOW, sanitize: passThrough }));
  assert.match(html, /<svg/);
  assert.doesNotMatch(html, /&lt;svg/);
  assert.match(html, /<li>one<\/li>/);
  assert.match(html, /<strong>A<\/strong>/);
  assert.match(html, /Pick a style\?/);
});

test("an ask with no remainder renders its headline alone, as before", () => {
  const html = renderToStaticMarkup(React.createElement(HitlpTask,
    { row: { ...ask("Pick a style?"), why: null }, now: NOW, sanitize: passThrough }));
  assert.match(html, /Pick a style\?/);
  assert.doesNotMatch(html, /htQuestionMore/);
});

test("a choice ask draws Something else; picked, it shows the box and Answer, which sends the text and refuses an empty box", () => {
  const row = { kind: "ask", actionId: "c-1", question: "Which store?", responseSchema: { type: "string" },
    options: [{ value: "pg", label: "Postgres" }, { value: "redis", label: "Redis" }], attested: { requester: "frakworks-engine" }, lineage: [] };
  const closed = draw(row);
  assert.match(closed, />Postgres<\/button>.*>Redis<\/button>.*>Something else<\/button>/s);
  assert.doesNotMatch(closed, /class="htAnswer"/, "no box until Something else is picked");
  assert.doesNotMatch(closed, /htOtherAnswer/);
  const open = draw(row, { initialOther: true });
  assert.match(open, /class="htAnswer"[^>]*type="text"/);
  assert.match(open, /class="htBtn htOtherAnswer"[^>]*>Answer<\/button>/);
  assert.match(open, /class="htBtn picked"[^>]*>Something else/);
  const { buttonsFor, pressOf } = require("../dist/needs-you.cjs");
  const spec = buttonsFor(row);
  const other = spec.buttons.find((b) => b.other);
  assert.deepEqual(pressOf(spec, other, false, "MySQL"), { reveal: true }, "the click reveals the box");
  assert.deepEqual(pressOf(spec, { label: "Answer", outcome: "answered", other: true }, true, "MySQL"), { answer: "MySQL" }, "Answer sends the text");
  assert.deepEqual(pressOf(spec, { label: "Answer", outcome: "answered", other: true }, true, ""), { error: "type an answer first" });
  assert.deepEqual(pressOf(spec, spec.buttons[1], false, "ignored"), { answer: "redis" }, "an option sends its value");
});
