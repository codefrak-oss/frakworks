// The main-process side of the ticket detail view (stage 2, #1281): the one
// engine read it makes, GET /ticket/{owner}/{repo}/{n} (#239), the document it
// maps that onto, and the promise that it never rejects. The lifecycle/cost/
// runs history lives in the Timeline widget (#101), fed by the engine's own
// `/ticket/{owner}/{repo}/{n}/timeline` (#91) — see test/timeline.test.js.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { fetchTicketData, parseLines, commentRevisionOf, repoOfIssueUrl } = require("../src/ticket-data");

// A stand-in for engine-data's fetchTicketDetail: records its calls and
// answers with `result` (an object, an Error to throw, or a function).
const fakeDetail = (result) => {
  const calls = [];
  const f = async (owner, repo, n, opts) => {
    calls.push({ owner, repo, n, opts });
    const r = typeof result === "function" ? result(owner, repo, n) : result;
    if (r instanceof Error) throw r;
    return r;
  };
  f.calls = calls;
  return f;
};

// What the engine answers for one ticket (engine/.../github/IssueDetails.kt).
const engineTicket = (over = {}) => ({
  repo: "codefrak-oss/frakworks", nodeId: "I_kw1281", number: 1281, title: "t",
  body: "Goal: do it", bodyHTML: "<p>Goal: do it</p>", state: "OPEN",
  url: "https://github.com/codefrak-oss/frakworks/issues/1281", labels: ["bot-task", "p3"],
  createdAt: "2026-09-21T00:00:00Z", closedAt: null,
  comments: [{ id: "IC_1", url: "c1", author: "example-smith", createdAt: "2026-09-21T00:10:00Z",
    updatedAt: "2026-09-21T00:12:00Z", body: "Done. ok", bodyHTML: "<p>Done. ok</p>" }],
  prs: [
    { number: 7, title: "fix", url: "p7", state: "OPEN", isDraft: true, reviewDecision: null },
    { number: 8, title: "fix2", url: "p8", state: "MERGED", isDraft: false, reviewDecision: "APPROVED", mergeCommitSha: "abc123" },
    { number: 9, title: "fix3", url: "p9", state: "OPEN", isDraft: false, reviewDecision: "CHANGES_REQUESTED" },
  ],
  ...over,
});

test("parseLines tolerates the blank lines VictoriaLogs streams (still used by transcript-data.js)", () => {
  assert.deepEqual(parseLines('{"a":1}\n\n {"b":2} \n\n'), [{ a: 1 }, { b: 2 }]);
  assert.deepEqual(parseLines(""), []);
});

test("one engine read, with the repo split into owner and name", async () => {
  const f = fakeDetail({ ticket: engineTicket(), error: null });
  await fetchTicketData(1281, { repo: "codefrak-oss/frakworks", fetchDetail: f });
  assert.equal(f.calls.length, 1);
  assert.deepEqual([f.calls[0].owner, f.calls[0].repo, f.calls[0].n], ["codefrak-oss", "frakworks", 1281]);
});

test("the repo is the caller's: no default repo, and no repo is an error (#577)", async () => {
  {
    const f = fakeDetail({ ticket: engineTicket(), error: null });
    const d = await fetchTicketData(1281, { fetchDetail: f });
    assert.equal(f.calls.length, 0);
    assert.ok(d.issue_error);
    const g = fakeDetail({ ticket: engineTicket(), error: null });
    await fetchTicketData(5, { repo: "a/b", fetchDetail: g });
    assert.deepEqual([g.calls[0].owner, g.calls[0].repo, g.calls[0].n], ["a", "b", 5]);
  }
});

test("no logs_url: the ticket document still renders, and a VictoriaLogs read names the missing key", async () => {
  const { postLogs } = require("../src/ticket-data");
  const d = await fetchTicketData(1281, { repo: "codefrak-oss/frakworks", fetchDetail: fakeDetail({ ticket: engineTicket(), error: null }) });
  assert.equal(d.issue_error, null);
  assert.ok(d.issue);
  await assert.rejects(postLogs({ base: null, path: "select/logsql/query", query: "x" }), /`logs_url` is not set in agent-army\.config\.json/);
});

test("a whole document: the engine's ticket mapped onto what the view and the pins read", async () => {
  const d = await fetchTicketData(1281, {
    repo: "codefrak-oss/frakworks", now: () => 1789909840,
    fetchDetail: fakeDetail({ ticket: engineTicket(), error: null }),
  });
  assert.equal(d.issue_error, null, d.issue_error);
  assert.equal(d.prs_error, null, d.prs_error);
  assert.equal(d.ticket, 1281);
  assert.equal(d.now, 1789909840);
  const c = engineTicket().comments[0];
  assert.deepEqual(d.issue, {
    node_id: "I_kw1281", number: 1281, title: "t", body: "Goal: do it", state: "OPEN",
    url: "https://github.com/codefrak-oss/frakworks/issues/1281", labels: ["bot-task", "p3"], points: null,
    createdAt: "2026-09-21T00:00:00Z", closedAt: null, askedBy: null, implementationSummary: null,
    comments: [{ id: "IC_1", revision: commentRevisionOf(c), author: "example-smith",
      createdAt: "2026-09-21T00:10:00Z", updatedAt: "2026-09-21T00:12:00Z", body: "Done. ok", url: "c1" }],
  });
  assert.match(d.issue.comments[0].revision, /^2026-09-21T00:12:00Z:[0-9a-f]{12}$/);
  assert.equal(d.body_html, "<p>Goal: do it</p>");
  assert.deepEqual(d.comment_html, { c1: "<p>Done. ok</p>" });
  assert.deepEqual(d.prs, [
    { number: 7, title: "fix", url: "p7", state: "DRAFT", review: null, mergeSha: null },
    { number: 8, title: "fix2", url: "p8", state: "MERGED", review: "APPROVED", mergeSha: "abc123" },
    { number: 9, title: "fix3", url: "p9", state: "OPEN", review: "CHANGES_REQUESTED", mergeSha: null },
  ]);
});

// #355: the ticket was asked for under its pre-move name (example-org/example-repo);
// GitHub followed the move and the engine's read answered with the issue's
// current URL. The engine recorded its process instances under that current
// name, so the Timeline must ask under it, or it gets a 404.
test("issue_repo is the repo the issue's URL names, not the one asked for (a moved repo)", async () => {
  const d = await fetchTicketData(353, {
    repo: "example-org/example-repo",
    fetchDetail: fakeDetail({ ticket: engineTicket({ number: 353, url: "https://github.com/codefrak-oss/frakworks/issues/353" }), error: null }),
  });
  assert.equal(d.repo, "example-org/example-repo", "repo stays what was asked for");
  assert.equal(d.issue_repo, "codefrak-oss/frakworks");
});

test("issue_repo falls back to the asked-for repo without a usable URL, and is null on failure", async () => {
  const d = await fetchTicketData(5, { repo: "o/r", fetchDetail: fakeDetail({ ticket: engineTicket({ url: null }), error: null }) });
  assert.equal(d.issue_repo, "o/r");
  const e = await fetchTicketData(5, { repo: "o/r", fetchDetail: fakeDetail({ error: "boom" }) });
  assert.equal(e.issue_repo, null);
});

test("repoOfIssueUrl reads owner/name off a GitHub issue URL only", () => {
  assert.equal(repoOfIssueUrl("https://github.com/codefrak-oss/frakworks/issues/353"), "codefrak-oss/frakworks");
  assert.equal(repoOfIssueUrl("https://github.com/o/r/pull/3"), null);
  assert.equal(repoOfIssueUrl("https://example.com/o/r/issues/3"), null);
  assert.equal(repoOfIssueUrl(null), null);
});

test("an edited comment reads as a new revision", async () => {
  const a = await fetchTicketData(1281, { repo: "o/r", fetchDetail: fakeDetail({ ticket: engineTicket(), error: null }) });
  const edited = engineTicket();
  edited.comments[0].body = "Done. changed";
  const b = await fetchTicketData(1281, { repo: "o/r", fetchDetail: fakeDetail({ ticket: edited, error: null }) });
  assert.equal(a.issue.comments[0].id, b.issue.comments[0].id);
  assert.notEqual(a.issue.comments[0].revision, b.issue.comments[0].revision);
});

test("an engine failure sets issue_error and prs_error both", async () => {
  for (const r of [
    { error: "no such ticket in a target repo: o/r#1281" },
    { error: "engine unreachable at http://engine.test/ticket/o/r/1281: ECONNREFUSED" },
    new Error("boom"),
  ]) {
    const d = await fetchTicketData(1281, { repo: "o/r", fetchDetail: fakeDetail(r) });
    const why = r instanceof Error ? r.message : r.error;
    assert.ok(d.issue_error.includes(why), d.issue_error);
    assert.equal(d.prs_error, d.issue_error);
    assert.equal(d.issue, null);
    assert.deepEqual(d.prs, []);
    assert.deepEqual(d.comment_html, {});
  }
});

test("a bad ticket number never reaches the engine", async () => {
  for (const bad of [null, "", "abc", -1, 0, 1.5]) {
    const f = fakeDetail(new Error("must not be called"));
    const d = await fetchTicketData(bad, { repo: "o/r", fetchDetail: f });
    assert.match(d.issue_error, /not a ticket number/, `rejected ${JSON.stringify(bad)}`);
    assert.match(d.prs_error, /not a ticket number/, `rejected ${JSON.stringify(bad)}`);
    assert.equal(f.calls.length, 0);
  }
});

test("ticket-data.js runs no process and no gh", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "ticket-data.js"), "utf8");
  assert.doesNotMatch(src, /child_process/);
  assert.doesNotMatch(src, /require\("\.\/gh"\)/);
});

test("toDocument carries the engine's story points, null when it has none", () => {
  const { toDocument } = require("../src/ticket-data");
  assert.equal(toDocument(engineTicket({ points: 3 }), "codefrak-oss/frakworks", 1281).issue.points, 3);
  assert.equal(toDocument(engineTicket(), "codefrak-oss/frakworks", 1281).issue.points, null);
});

test("the engine's implementationSummary is carried onto the issue, trimmed; blank is null", async () => {
  const of = async (implementationSummary) => (await fetchTicketData(1281, {
    repo: "codefrak-oss/frakworks", now: () => 1789909840,
    fetchDetail: fakeDetail({ ticket: { ...engineTicket(), implementationSummary }, error: null }),
  })).issue.implementationSummary;
  assert.equal(await of("  Adds the field.  "), "Adds the field.");
  assert.equal(await of("   "), null);
  assert.equal(await of(undefined), null);
});
