// engine-update-changes.test.js — what an engine update brings (#1398,
// src/engine-update-changes.js): squash subjects to PRs and tickets, and the
// range read against a stand-in git with every reason the list can be missing.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { parseSubject, parseChanges, listChanges, changesLines } = require("../src/engine-update-changes");
const { createEngineUpdater, engineUpdateTitle } = require("../src/engine-update");

const A = "a".repeat(40), B = "b".repeat(40), C = "c".repeat(40), D = "d".repeat(40);
const LOG = [
  `${C}\tEngine: system-owned refactoring jobs (#1352) (#1372)`,
  `${D}\tProposal: the engine on Kubernetes (#1384)`,
  `${A}\tREADME: a direct push`,
  `${B}\tDoctor: two tickets (#10) (#11) (#12)`,
  "not a line",
].join("\n");

test("parseSubject: the last (#n) is the PR, the ones before it the tickets", () => {
  assert.deepEqual(parseSubject("Fix (#1) (#2)"), { title: "Fix", pr: 2, tickets: [1] });
  assert.deepEqual(parseSubject("Fix (#2)"), { title: "Fix", pr: 2, tickets: [] });
  assert.deepEqual(parseSubject("Fix (#1) in the middle"), { title: "Fix (#1) in the middle", pr: null, tickets: [] });
});

test("parseChanges: PR commits with GitHub and frakworks links, noise left out", () => {
  const c = parseChanges(LOG, "o/r");
  assert.equal(c.length, 3);
  assert.deepEqual(c[0], { sha: C, title: "Engine: system-owned refactoring jobs", pr: 1372, prUrl: "https://github.com/o/r/pull/1372",
    tickets: [{ n: 1352, repo: "o/r", url: "frakworks://ticket/o/r/1352" }] });
  assert.deepEqual(c[1].tickets, []);
  assert.deepEqual(c[2].tickets.map((t) => t.n), [10, 11]);
  assert.equal(c[2].pr, 12);
});

const fakeGit = ({ known = [A, B], afterFetch = [], fetchFails = false, logFails = false } = {}) => {
  const have = new Set(known), calls = [];
  const git = async (args) => {
    calls.push(args.join(" "));
    if (args[0] === "rev-parse") { if (have.has(args[3].replace("^{commit}", ""))) return A; throw new Error("unknown"); }
    if (args[0] === "fetch") { if (fetchFails) throw new Error("no network"); afterFetch.forEach((s) => have.add(s)); return ""; }
    if (args[0] === "log") { if (logFails) throw new Error("boom"); return LOG; }
    throw new Error("unexpected");
  };
  return { git, calls };
};

test("listChanges: the range's PRs, no fetch when both commits are there", async () => {
  const g = fakeGit();
  const r = await listChanges({ running: A, available: B, git: g.git });
  assert.equal(r.changes.length, 3);
  assert.ok(g.calls.includes(`log --first-parent --format=%H%x09%s ${A}..${B}`));
  assert.ok(!g.calls.some((c) => c.startsWith("fetch")));
});

test("listChanges: a missing commit is fetched once", async () => {
  const g = fakeGit({ known: [A], afterFetch: [B] });
  assert.equal((await listChanges({ running: A, available: B, git: g.git })).changes.length, 3);
  assert.equal(g.calls.filter((c) => c.startsWith("fetch")).length, 1);
});

test("listChanges: every reason the list is missing", async () => {
  assert.match((await listChanges({ running: null, available: B, git: fakeGit().git })).error, /no commit/);
  assert.match((await listChanges({ running: A, available: null, git: fakeGit().git })).error, /revision label/);
  assert.match((await listChanges({ running: A, available: B, git: null })).error, /git checkout/);
  assert.match((await listChanges({ running: A, available: B, git: fakeGit({ known: [A] }).git })).error, /bbbbbbb is not on origin main/);
  assert.match((await listChanges({ running: A, available: B, git: fakeGit({ known: [A], fetchFails: true }).git })).error, /fetching origin main failed: no network/);
  assert.match((await listChanges({ running: A, available: B, git: fakeGit({ logFails: true }).git })).error, /git log failed: boom/);
});

test("listChanges: the same commit brings nothing", async () => {
  assert.deepEqual(await listChanges({ running: A, available: A, git: null }), { changes: [] });
});

test("changesLines: the list, the reason, and nothing before a read", () => {
  assert.deepEqual(changesLines(null), []);
  assert.match(changesLines({ error: "x" })[0], /missing: x/);
  const l = changesLines({ changes: parseChanges(LOG) });
  assert.equal(l[0], "3 PRs merged:");
  assert.match(l[1], /#1372 Engine: system-owned refactoring jobs \(closes #1352\)/);
});

test("the updater reads the changes once per commit pair while the digests differ, and they reach the hover and confirm", async () => {
  const OLD = `sha256:${"1".repeat(64)}`, NEW = `sha256:${"2".repeat(64)}`;
  const reg = "r:5000", ref = `${reg}/frakworks-engine:main`;
  const fetch = async (url) => {
    const res = (status, text, digest) => ({ ok: status === 200, status, text: async () => text, headers: { get: (h) => (h === "docker-content-digest" ? digest : null) } });
    if (url.endsWith("/manifests/main")) return res(200, JSON.stringify({ config: { digest: "sha256:cfg" } }), NEW);
    if (url.includes("/blobs/")) return res(200, JSON.stringify({ config: { Labels: { "org.opencontainers.image.revision": B } } }));
    if (url.endsWith("/version")) return res(200, JSON.stringify({ commit: A }));
    if (url.endsWith("/metrics")) return res(200, "");
    return res(404, "");
  };
  const run = async (cmd, args) => {
    if (args[0] === "ps") return { code: 0, stdout: args.includes("status=created") ? "" : "c1\n", stderr: "" };
    if (args[0] === "inspect") return { code: 0, stdout: JSON.stringify([{ Image: "img", Config: { Image: ref, Labels: {} } }]), stderr: "" };
    if (args[0] === "image") return { code: 0, stdout: JSON.stringify([`${ref.replace(":main", "")}@${OLD}`]), stderr: "" };
    return { code: 1, stdout: "", stderr: "" };
  };
  let asked = 0;
  const u = createEngineUpdater({ run, fetch, engineUrl: "http://localhost:8090", registry: reg,
    changes: async ({ running, available }) => { asked++; return listChanges({ running, available, git: fakeGit().git }); } });
  await u.check();
  await u.check();
  assert.equal(asked, 1);
  const st = u.state();
  assert.equal(st.changes.changes.length, 3);
  assert.match(engineUpdateTitle(st), /3 PRs merged:/);
});

test("without a checkout the engine's list stands in whole (#100091)", async () => {
  const asked = [];
  const remote = async (from, to) => { asked.push(`${from}..${to}`); return { changes: [{ sha: C, title: "T", pr: 2, prUrl: "u", tickets: [{ n: 1, repo: "o/r", url: "frakworks://ticket/o/r/1", title: "One" }] }] }; };
  const r = await listChanges({ running: A, available: B, git: null, remote });
  assert.deepEqual(asked, [`${A}..${B}`]);
  assert.equal(r.changes[0].tickets[0].title, "One");
  assert.deepEqual(await listChanges({ running: A, available: B, git: null, remote: async () => ({ error: "engine answered 502" }) }), { error: "engine answered 502" });
  assert.deepEqual(await listChanges({ running: null, available: B, remote, noRunning: "this build was not stamped with its commit" }), { error: "this build was not stamped with its commit" });
});

test("with a checkout, git gives the range and the engine only the titles; a failed engine read leaves the list as it is", async () => {
  const git = async (args) => (args[0] === "log" ? LOG : "");
  const remote = async () => ({ changes: [{ tickets: [{ n: 10, repo: "codefrak-oss/frakworks", title: "Ten" }, { n: 11, repo: "codefrak-oss/frakworks", title: null }] }] });
  const r = await listChanges({ running: A, available: B, git, remote });
  const doctor = r.changes.find((c) => c.pr === 12);
  assert.deepEqual(doctor.tickets.map((t) => t.title), ["Ten", null]);
  const down = await listChanges({ running: A, available: B, git, remote: async () => { throw new Error("unreachable"); } });
  assert.equal(down.changes.length, 3);
  assert.deepEqual(down.changes.find((c) => c.pr === 12).tickets.map((t) => t.n), [10, 11]);
});
