// update-preview.test.js — the update preview modal (#100091): its view model
// (src/update-preview.js) and its drawing (src/renderer/update-preview.jsx)
// for the four states the mockups show: desktop update ready, engine update
// ready, list missing, nothing merged.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { previewModel, versionHeader, rowsFrom, missingLine, emptyLine, withCap, MAX_ROWS } = require("../src/update-preview");
const { UpdatePreview } = require("../dist/update-preview.cjs");

const R = "codefrak-oss/frakworks";
const t = (n, title) => ({ n, repo: R, url: `frakworks://ticket/${R}/${n}`, title });
const CHANGES = [
  { sha: "a", title: "Bump esbuild to 0.28", pr: 2713, prUrl: `https://github.com/${R}/pull/2713`, tickets: [] },
  { sha: "b", title: "Choice questions", pr: 2720, prUrl: "u", tickets: [t(2712, "Choice questions offer a text box"), t(2709, null)] },
];
const draw = (preview, snapshot = null) => renderToStaticMarkup(React.createElement(UpdatePreview, { preview, snapshot, onUpdate() {}, onCancel() {} }));

test("the version header: desktop versions, engine build labels with how far behind, ? for an unknown end", () => {
  assert.deepEqual(versionHeader({ kind: "desktop", running: { version: "0.1.412" }, incoming: { version: "0.1.418" } }),
    { running: "v0.1.412", incoming: "v0.1.418", runningLabel: "running", incomingLabel: "incoming", behind: "" });
  const e = versionHeader({ kind: "engine", running: { commit: "76600fb" + "0".repeat(33) }, incoming: { commit: "a1b2c3d" + "0".repeat(33) }, behind: 3 });
  assert.equal(`${e.running} → ${e.incoming} ${e.behind}`, "sha-76600fb → sha-a1b2c3d (3 commits behind)");
  assert.equal(versionHeader({ kind: "desktop", running: null, incoming: { version: "0.1.418" } }).running, "?");
});

test("rows: one per ticket with its title or null, a PR row when the PR names none", () => {
  assert.deepEqual(rowsFrom(CHANGES), [
    { kind: "pr", pr: 2713, url: `https://github.com/${R}/pull/2713`, title: "Bump esbuild to 0.28" },
    { kind: "ticket", n: 2712, url: `frakworks://ticket/${R}/2712`, title: "Choice questions offer a text box" },
    { kind: "ticket", n: 2709, url: `frakworks://ticket/${R}/2709`, title: null },
  ]);
  const many = Array.from({ length: MAX_ROWS + 5 }, (_, i) => ({ pr: i, title: `p${i}`, tickets: [] }));
  const m = previewModel({ kind: "desktop", running: {}, incoming: {}, list: { changes: many } });
  assert.equal(m.rows.length, MAX_ROWS);
  assert.equal(m.more, 5);
});

test("the missing line for { error } and the empty line for no changes; neither with a list", () => {
  assert.equal(missingLine({ error: "this build was not stamped with its commit" }), "The change list is missing: this build was not stamped with its commit.");
  assert.equal(missingLine({ changes: [] }), null);
  assert.equal(emptyLine({ changes: [] }), "No changes between the two builds (same commit, rebuilt image).");
  assert.equal(emptyLine({ changes: CHANGES }), null);
  assert.equal(emptyLine({ error: "x" }), null);
});

test("withCap: the list, or the engine's silence said once the cap passes", async () => {
  assert.deepEqual(await withCap(Promise.resolve({ changes: [] }), 1000), { changes: [] });
  assert.deepEqual(await withCap(new Promise(() => {}), 10), { error: "the engine did not answer within 0 s" });
  assert.deepEqual(await withCap(Promise.reject(new Error("boom")), 1000), { error: "boom" });
});

test("desktop update ready: the header, ticket links then titles, title unavailable, the PR row, Cancel and Update", () => {
  const m = previewModel({ kind: "desktop", running: { version: "0.1.412" }, incoming: { version: "0.1.418" }, list: { changes: CHANGES } });
  assert.deepEqual(m.lines, []);
  const html = draw(m, "data:image/png;base64,AAAA");
  assert.match(html, /Update the desktop app\?/);
  assert.match(html, /v0\.1\.412.*→.*v0\.1\.418/);
  assert.match(html, /What this update brings · 2 changes/);
  assert.match(html, /<a class="upNum" href="frakworks:\/\/ticket\/codefrak-oss\/frakworks\/2712">#2712<\/a><span class="upTitle">Choice questions offer a text box<\/span>/);
  assert.match(html, /#2709<\/a><span class="upTitle upUnknown">title unavailable<\/span>/);
  assert.match(html, /PR #2713<\/a><span class="upTitle">Bump esbuild to 0\.28/);
  assert.match(html, /class="uoBackdrop upSnap"/);
  assert.match(html, /class="upSnapshot"/);
  assert.match(html, />Cancel<\/button><button class="upUpdate"[^>]*>Update<\/button>/);
  assert.match(html, /Update installs v0\.1\.418 and relaunches the app\. Cancel keeps v0\.1\.412 running\./);
});

test("engine update ready, list missing, nothing merged: each still offers Update", () => {
  const eng = previewModel({ kind: "engine", running: { commit: "1".repeat(40) }, incoming: { commit: "2".repeat(40) }, behind: 1, list: { changes: CHANGES }, liveRuns: 0, sessions: 0, remote: true });
  const html = draw(eng);
  assert.match(html, /Update the engine\?/);
  assert.match(html, /No Smith runs are live/);
  assert.match(html, /No first-mate sessions are open/);
  assert.match(html, /class="uoBackdrop upBlur"/, "no snapshot: the scrim blurs (the web version)");
  const missing = draw(previewModel({ kind: "desktop", running: { version: "1" }, incoming: { version: "2" }, list: { error: "the engine did not answer within 4 s" } }));
  assert.match(missing, /class="upMissingWhy">The change list is missing: the engine did not answer within 4 s\.</);
  assert.doesNotMatch(missing, /upRows/);
  assert.match(missing, />Update<\/button>/);
  const none = draw(previewModel({ kind: "engine", running: { commit: "1".repeat(40) }, incoming: { commit: "1".repeat(40) }, list: { changes: [] }, liveRuns: 0, sessions: 0 }));
  assert.match(none, /No changes between the two builds/);
  assert.match(none, />Update<\/button>/);
  assert.equal(draw(null), "");
});
