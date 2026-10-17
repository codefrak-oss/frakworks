// The explorer's Repos branch (#674): a target repo's files at its default
// branch, read from the engine's GET /repos/{owner}/{repo}/tree and /file.
// One tree call per repo when it is first opened, none for its folders (they
// are built here from the paths), one file call per click, ⟳ re-reads the open
// repos, nothing on a timer; markdown drawn locally, binaries and big files cut.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { buildTree, fileCount, fileView, formatSize, repoFileId, parseRepoFileId, MAX_SHOWN_BYTES } = require("../src/repo-files");
const { fetchRepoTree, fetchRepoFile } = require("../src/engine-data");
const { ReposBranch, RepoFileBody, RepoFileView, loadRepoFile, markdownHtml } = require("../dist/repo-files.cjs");
const { Explorer } = require("../dist/explorer.cjs");

const FIX = JSON.parse(fs.readFileSync(path.join(__dirname, "fixture-repo-tree.json"), "utf8"));
const src = (f) => fs.readFileSync(path.join(__dirname, "..", "src", f), "utf8");

// A hook harness (as in accept-card.test.js): the branch is called as a
// function with useState stood in, so its rows can be pressed and it redrawn.
const mount = (Component, props) => {
  const slots = [];
  const real = { useState: React.useState, useEffect: React.useEffect };
  const draw = () => {
    let i = 0;
    React.useState = (init) => {
      const k = i++;
      if (!(k in slots)) slots[k] = typeof init === "function" ? init() : init;
      return [slots[k], (v) => { slots[k] = typeof v === "function" ? v(slots[k]) : v; }];
    };
    React.useEffect = () => {};
    try { return Component(props); } finally { Object.assign(React, real); }
  };
  return { draw };
};
const walk = (el, out = []) => {
  if (!el || typeof el !== "object") return out;
  if (Array.isArray(el)) { for (const c of el) walk(c, out); return out; }
  out.push(el);
  if (el.props) walk(el.props.children, out);
  return out;
};
const labelOf = (row) => walk(row).filter((e) => e.props && e.props.className === "exLabel").map((e) => String(e.props.children))[0];
const rows = (tree) => walk(tree).filter((e) => e.type === "div" && /\bexRow\b/.test(String(e.props.className)));
const row = (tree, label) => rows(tree).find((r) => labelOf(r) === label);
const labels = (tree) => rows(tree).map(labelOf);
const settle = () => new Promise((r) => setImmediate(r));

const fakeFleet = (tree = FIX) => {
  const calls = { repoTree: [], repoFile: [] };
  return {
    calls,
    repoTree: async (owner, name) => { calls.repoTree.push(`${owner}/${name}`); return { tree: JSON.parse(JSON.stringify(tree)), error: null }; },
    repoFile: async (...a) => { calls.repoFile.push(a); return { file: { bytes: new TextEncoder().encode("# hi") } }; },
  };
};

test("buildTree nests the flat entries: folders first, then by name, submodules kept, missing folders made", () => {
  const nodes = buildTree(FIX.entries);
  assert.deepEqual(nodes.map((n) => [n.name, n.type]), [
    ["docs", "tree"], ["engine", "tree"], ["a.txt", "blob"], ["README.md", "blob"], ["vendor", "commit"], ["Zeta.txt", "blob"]]);
  const docs = nodes[0];
  assert.deepEqual(docs.children.map((n) => n.path), ["docs/img", "docs/guide.md"]);
  assert.equal(docs.children[0].children[0].path, "docs/img/logo.png");
  // A truncated tree may name a file without its folder.
  assert.deepEqual(buildTree([{ path: "x/y/z.txt", type: "blob", sha: "1" }]).map((n) => [n.path, n.children[0].path]), [["x", "x/y"]]);
  assert.equal(fileCount(FIX.entries), 6);
});

test("opening a repo asks for its tree once; its folders open and close with no IPC call", async () => {
  const fleet = fakeFleet();
  const picked = [];
  const b = mount(ReposBranch, { repos: ["o/r"], sel: null, onSelect: (i) => picked.push(i), fleet });
  row(b.draw(), "Repos").props.onClick();
  assert.deepEqual(fleet.calls.repoTree, [], "nothing asked until a repo is opened");
  row(b.draw(), "o/r").props.onClick();
  assert.ok(labels(b.draw()).length && walk(b.draw()).some((e) => e.props && e.props.children === "loading…"));
  await settle();
  assert.deepEqual(fleet.calls.repoTree, ["o/r"]);
  assert.deepEqual(labels(b.draw()), ["Repos", "o/r", "docs", "engine", "a.txt", "README.md", "vendor", "Zeta.txt"]);
  row(b.draw(), "docs").props.onClick();
  row(b.draw(), "img").props.onClick();
  row(b.draw(), "docs").props.onClick();
  assert.equal(row(b.draw(), "img"), undefined, "docs closed");
  row(b.draw(), "docs").props.onClick();
  assert.deepEqual(labels(b.draw()).slice(0, 6), ["Repos", "o/r", "docs", "img", "logo.png", "guide.md"]);
  row(b.draw(), "docs").props.onClick();
  row(b.draw(), "docs").props.onClick();
  row(b.draw(), "o/r").props.onClick(); // closed
  row(b.draw(), "o/r").props.onClick(); // open again: kept
  await settle();
  assert.deepEqual(fleet.calls.repoTree, ["o/r"], "one tree call, whatever the folders do");
  assert.deepEqual(fleet.calls.repoFile, []);
  // A file row selects the file at the tree's commit; a submodule is not a file.
  row(b.draw(), "guide.md").props.onClick();
  assert.deepEqual(picked, [{ kind: "repo-file", id: "o/r:docs/guide.md", ref: FIX.commit }]);
  assert.equal(row(b.draw(), "vendor").props.onClick, undefined);
  assert.deepEqual(fleet.calls.repoFile, [], "the branch reads no file; the view does");
});

test("a truncated tree says so", async () => {
  const b = mount(ReposBranch, { repos: ["o/r"], sel: null, onSelect: () => {}, fleet: fakeFleet({ ...FIX, truncated: true }) });
  row(b.draw(), "Repos").props.onClick();
  row(b.draw(), "o/r").props.onClick();
  await settle();
  const notice = walk(b.draw()).find((e) => e.props && /\bexTruncated\b/.test(String(e.props.className)));
  assert.match(String(notice.props.children), /tree truncated/);
  const whole = mount(ReposBranch, { repos: ["o/r"], sel: null, onSelect: () => {}, fleet: fakeFleet() });
  row(whole.draw(), "Repos").props.onClick();
  row(whole.draw(), "o/r").props.onClick();
  await settle();
  assert.equal(walk(whole.draw()).find((e) => e.props && /\bexTruncated\b/.test(String(e.props.className))), undefined);
});

test("⟳ drops the kept trees and re-reads only the repos that are open", async () => {
  const fleet = fakeFleet();
  const b = mount(ReposBranch, { repos: ["o/a", "o/b", "o/c"], sel: null, onSelect: () => {}, fleet });
  row(b.draw(), "Repos").props.onClick();
  row(b.draw(), "o/a").props.onClick();
  row(b.draw(), "o/b").props.onClick();
  await settle();
  row(b.draw(), "o/b").props.onClick(); // closed, its tree kept
  assert.deepEqual(fleet.calls.repoTree, ["o/a", "o/b"]);
  let stopped = false;
  const refresh = walk(b.draw()).find((e) => e.type === "button" && /\breposRefresh\b/.test(e.props.className));
  assert.equal(refresh.props.children, "⟳");
  refresh.props.onClick({ stopPropagation: () => { stopped = true; } });
  await settle();
  assert.ok(stopped, "the branch row does not also toggle");
  assert.deepEqual(fleet.calls.repoTree, ["o/a", "o/b", "o/a"]);
  row(b.draw(), "o/b").props.onClick(); // its tree was dropped: read again on opening
  await settle();
  assert.deepEqual(fleet.calls.repoTree, ["o/a", "o/b", "o/a", "o/b"]);
  assert.doesNotMatch(src("renderer/repo-files.jsx") + src("repo-files.js"), /setInterval|setTimeout/, "nothing re-reads on a timer");
});

test("two repos sharing paths: toggling folders draws each repo's rows once, under unique keys, with no new tree call", async () => {
  const shared = { commit: "c1", truncated: false, entries: [
    { path: "README.md", type: "blob", sha: "r" }, { path: "docs", type: "tree", sha: "d" },
    { path: "docs/README.md", type: "blob", sha: "dr" }, { path: "docs/guide.md", type: "blob", sha: "g" }] };
  const fleet = fakeFleet(shared);
  const b = mount(ReposBranch, { repos: ["o/a", "o/b"], sel: null, onSelect: () => {}, fleet });
  row(b.draw(), "Repos").props.onClick();
  row(b.draw(), "o/a").props.onClick();
  row(b.draw(), "o/b").props.onClick();
  await settle();
  const docsOf = (repo) => rows(b.draw()).filter((r) => labelOf(r) === "docs").find((r) => String(r.key).includes(repo));
  const shown = () => rows(b.draw()).filter((r) => r.key != null).map((r) => [labelOf(r), r.props.style.paddingLeft]);
  const keysUnique = () => {
    const keys = rows(b.draw()).filter((r) => r.key != null).map((r) => r.key);
    assert.equal(new Set(keys).size, keys.length, `duplicate keys: ${keys}`);
    assert.ok(keys.filter((k) => /README\.md$/.test(k)).every((k) => /o\/[ab]/.test(k)), "each file row's key names its repo");
  };
  const pad = (depth) => 8 + depth * 14; // as the renderer draws it
  const repo = (name, docsOpen) => [[name, pad(1)], ["docs", pad(2)],
    ...(docsOpen ? [["guide.md", pad(3)], ["README.md", pad(3)]] : []), ["README.md", pad(2)]];
  docsOf("o/a").props.onClick();
  docsOf("o/b").props.onClick();
  assert.deepEqual(shown(), [...repo("o/a", true), ...repo("o/b", true)]);
  keysUnique();
  docsOf("o/a").props.onClick();
  assert.deepEqual(shown(), [...repo("o/a", false), ...repo("o/b", true)]);
  keysUnique();
  docsOf("o/b").props.onClick();
  docsOf("o/a").props.onClick();
  assert.deepEqual(shown(), [...repo("o/a", true), ...repo("o/b", false)]);
  keysUnique();
  docsOf("o/b").props.onClick();
  assert.deepEqual(shown(), [...repo("o/a", true), ...repo("o/b", true)]);
  keysUnique();
  assert.deepEqual(fleet.calls.repoTree, ["o/a", "o/b"], "one tree call per repo, whatever the folders do");
});

test("a failed tree read shows its error in the repo", async () => {
  const fleet = { repoTree: async () => ({ error: "no tree for o/r: engine answered 404" }) };
  const b = mount(ReposBranch, { repos: ["o/r"], sel: null, onSelect: () => {}, fleet });
  row(b.draw(), "Repos").props.onClick();
  row(b.draw(), "o/r").props.onClick();
  await settle();
  assert.ok(walk(b.draw()).some((e) => e.props && e.props.className === "exErr" && /404/.test(e.props.children)));
});

test("the Explorer draws the Repos branch with the repos it is given", () => {
  const html = renderToStaticMarkup(React.createElement(Explorer, { sel: null, onSelect: () => {}, tree: null, browseRepos: ["o/r", "o/s"] }));
  assert.match(html, /<span class="exLabel">Repos<\/span><span class="exCount">2<\/span>/);
  assert.match(html, /reposRefresh/);
  // A restored file opens the branch.
  const open = renderToStaticMarkup(React.createElement(Explorer, { sel: { kind: "repo-file", id: "o/r:a.txt" }, onSelect: () => {}, tree: null, browseRepos: ["o/r"] }));
  assert.match(open, /title="o\/r"/);
  // The switcher's repo, or every target repo under All repos.
  assert.match(src("renderer/index.jsx"), /browseRepos=\{selection \? \(selRepo \? \[selRepo\] : selection\.repos\) : null\}/);
});

test("a file is one repoFile call, at the commit it was picked from", async () => {
  const fleet = fakeFleet();
  const r = await loadRepoFile({ id: repoFileId("o/r", "docs/guide.md"), ref: FIX.commit, fleet });
  assert.deepEqual(fleet.calls.repoFile, [["o", "r", "docs/guide.md", FIX.commit]]);
  assert.equal(new TextDecoder().decode(r.file.bytes), "# hi");
  assert.deepEqual(parseRepoFileId("o/r:a/b:c.txt"), { owner: "o", name: "r", repo: "o/r", path: "a/b:c.txt" });
  assert.match((await loadRepoFile({ id: "nope", fleet })).error, /not a repo file/);
  assert.match((await loadRepoFile({ id: "o/r:x", fleet: { repoFile: async () => { throw new Error("gone"); } } })).error, /IPC failed: gone/);
});

test("fileView: NUL or a binary extension is binary; text past 256 KiB is cut", () => {
  const enc = (s) => new TextEncoder().encode(s);
  assert.deepEqual(fileView("a.txt", Uint8Array.from([104, 0, 105])), { kind: "binary", size: 3 });
  assert.deepEqual(fileView("logo.png", enc("looks like text")), { kind: "binary", size: 15 });
  assert.equal(fileView("a.kt", enc("x".repeat(9000) + "\0")).kind, "text", "a NUL past 8 KiB is not looked for");
  assert.deepEqual(fileView("README.MD", enc("# hi")), { kind: "markdown", text: "# hi", size: 4, truncated: false });
  const big = fileView("big.log", new Uint8Array(MAX_SHOWN_BYTES + 10).fill(97));
  assert.equal(big.truncated, true);
  assert.equal(big.text.length, MAX_SHOWN_BYTES);
  assert.equal(big.size, MAX_SHOWN_BYTES + 10);
  assert.equal(formatSize(512), "512 B");
  assert.equal(formatSize(256 * 1024), "256 KiB");
  assert.equal(formatSize(3 * 1024 * 1024), "3.0 MiB");
});

test("the file view: binary not shown with its size, text highlighted in monospace, the 256 KiB line, markdown split", () => {
  const draw = (p, bytes, render) => renderToStaticMarkup(React.createElement(RepoFileBody, { path: p, result: { file: { bytes } }, render }));
  assert.match(draw("x.bin", Uint8Array.from([0, 1, 2])), /binary file, not shown \(3 B, 3 bytes\)/);
  const kt = draw("a.kt", new TextEncoder().encode("val a = 1"));
  assert.match(kt, /<pre class="repoText repoCode hljs language-kotlin"><code><span class="hljs-keyword">val<\/span> a = <span class="hljs-number">1<\/span><\/code><\/pre>/);
  const big = draw("big.log", new Uint8Array(MAX_SHOWN_BYTES + 1).fill(97));
  assert.match(big, /truncated at 256 KiB of 256 KiB \(262145 bytes\)/);
  assert.match(big, /language-plaintext/);
  const doc = draw("a.md", new TextEncoder().encode("# Hi"), (t) => markdownHtml(t, (h) => h));
  assert.match(doc, /<div class="repoSplit"><div class="repoPane repoRendered"><div class="repoDoc md"><h1>Hi<\/h1>/);
  assert.match(doc, /role="separator" aria-orientation="horizontal"/);
  assert.match(doc, /<div class="repoPane repoRaw"><pre class="repoText repoCode hljs language-markdown"><code><span class="hljs-section"># Hi<\/span>/);
  // No DOM to sanitize with (as here): the markdown source alone, never unsanitized.
  const raw = draw("a.md", new TextEncoder().encode("# Hi <script>x</script>"));
  assert.match(raw, /<pre class="repoText repoCode hljs language-markdown"><code><span class="hljs-section"># Hi /);
  assert.match(raw, /&lt;<span class="hljs-name">script<\/span>&gt;/);
  assert.doesNotMatch(raw, /repoSplit|<script/);
  assert.match(renderToStaticMarkup(React.createElement(RepoFileBody, { path: "a", result: { error: "not found: nope" } })), /not found: nope/);
});

test("a non-markdown file is one panel, no bar; a file with <script> shows it as text", () => {
  const draw = (p, text) => renderToStaticMarkup(React.createElement(RepoFileBody, { path: p, result: { file: { bytes: new TextEncoder().encode(text) } } }));
  for (const [p, text] of [["a.html", "<script>alert(1)</script>"], ["a.js", "const s = '<script>alert(1)</script>';"], ["notes.unknown", "<script>alert(1)</script>"]]) {
    const html = draw(p, text);
    assert.doesNotMatch(html, /<script/, p);
    assert.match(html, /&lt;(<span class="hljs-name">)?script/, p);
    assert.doesNotMatch(html, /repoSplit|separator/, `${p}: one panel`);
  }
});

test("nothing in the view is editable: no editor, no save, no line numbers, no scroll sync", () => {
  const md = renderToStaticMarkup(React.createElement(RepoFileBody, { path: "a.md", render: (t) => markdownHtml(t, (h) => h),
    result: { file: { bytes: new TextEncoder().encode("# T\n\ntext") } } }));
  const js = renderToStaticMarkup(React.createElement(RepoFileBody, { path: "a.js", result: { file: { bytes: new TextEncoder().encode("let a = 1;") } } }));
  for (const html of [md, js]) assert.doesNotMatch(html, /contenteditable|<textarea|<input|<button/i);
  const view = src("renderer/repo-files.jsx");
  assert.doesNotMatch(view, /contentEditable|onScroll|scrollTop|lineNumber/);
});

test("markdown is rendered locally: an image is its alt text, never a request", () => {
  const html = markdownHtml("# T\n\n![the logo](https://example.com/x.png) and [a link](https://example.com)\n\n| a |\n|---|\n| 1 |", (h) => h);
  assert.doesNotMatch(html, /<img|src=/);
  assert.match(html, /<span class="mdImage">\[the logo\]<\/span>/);
  assert.match(html, /<table>/);
  const esc = markdownHtml("![<b>x</b>](y.png)", (h) => h);
  assert.doesNotMatch(esc, /<b>/);
  const view = src("renderer/repo-files.jsx");
  assert.match(view, /DOMPurify\.sanitize\(html, \{ FORBID_TAGS, FORBID_ATTR \}\)/);
  for (const tag of ["img", "iframe", "script", "link", "style", "object", "embed", "video", "audio", "form"]) {
    assert.match(view, new RegExp(`"${tag}"`), `${tag} is forbidden`);
  }
});

const stubFetch = (status, body, calls = [], headers = {}) => async (url, init) => {
  calls.push({ url, init });
  const bytes = typeof body === "string" ? new TextEncoder().encode(body) : body;
  return { ok: status >= 200 && status < 300, status, text: async () => new TextDecoder().decode(bytes),
    arrayBuffer: async () => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength),
    headers: { get: (k) => headers[k.toLowerCase()] ?? null } };
};

test("fetchRepoTree reads GET /repos/<owner>/<repo>/tree; a bad repo never reaches the engine", async () => {
  const calls = [];
  const r = await fetchRepoTree("o", "r", { env: {}, fetch: stubFetch(200, JSON.stringify(FIX), calls) });
  assert.equal(r.error, null);
  assert.deepEqual(r.tree, { commit: FIX.commit, truncated: false, entries: FIX.entries });
  assert.match(calls[0].url, /\/repos\/o\/r\/tree$/);
  assert.match((await fetchRepoTree("o", "r", { env: {}, fetch: stubFetch(404, '{"error":"o/r is not a target repo"}') })).error, /no tree for o\/r/);
  assert.match((await fetchRepoTree("o", "r", { env: {}, fetch: stubFetch(200, "{}") })).error, /no `commit`/);
  const none = [];
  for (const [o, n] of [["o/x", "r"], ["o", ""], [5, "r"]]) assert.ok((await fetchRepoTree(o, n, { env: {}, fetch: stubFetch(200, "{}", none) })).error);
  assert.deepEqual(none, []);
});

test("fetchRepoFile reads GET /repos/<owner>/<repo>/file?path=&ref= as bytes", async () => {
  const calls = [];
  const bytes = Uint8Array.from([0, 255, 1]);
  const r = await fetchRepoFile("o", "r", "docs/a b.md", FIX.commit, { env: {}, fetch: stubFetch(200, bytes, calls, { "content-type": "application/octet-stream" }) });
  assert.equal(r.error, null);
  assert.deepEqual([...r.file.bytes], [0, 255, 1]);
  assert.equal(r.file.size, 3);
  assert.equal(r.file.contentType, "application/octet-stream");
  assert.match(calls[0].url, new RegExp(`/repos/o/r/file\\?path=docs%2Fa%20b\\.md&ref=${FIX.commit}$`));
  assert.match((await fetchRepoFile("o", "r", "x", null, { env: {}, fetch: stubFetch(404, '{"error":"no x in o/r"}') })).error, /^not found: no x in o\/r$/);
  assert.match((await fetchRepoFile("o", "r", "x", null, { env: {}, fetch: stubFetch(502, '{"error":"GitHub: 502"}') })).error, /engine answered 502/);
  const none = [];
  for (const p of ["../etc/passwd", "/abs", "a//b", "", "a\\b", 5]) assert.ok((await fetchRepoFile("o", "r", p, null, { env: {}, fetch: stubFetch(200, "x", none) })).error);
  assert.deepEqual(none, [], "nothing asked of the engine");
});

test("the two channels: preload asks, main answers through engine-data", () => {
  assert.match(src("preload.js"), /repoTree: \(owner, repoName\) => ipcRenderer\.invoke\("engine:repo-tree"/);
  assert.match(src("preload.js"), /repoFile: \(owner, repoName, path, ref\) => ipcRenderer\.invoke\("engine:repo-file"/);
  assert.match(src("main.js"), /ipcMain\.handle\("engine:repo-tree"[\s\S]*?fetchRepoTree\(/);
  assert.match(src("main.js"), /ipcMain\.handle\("engine:repo-file"[\s\S]*?fetchRepoFile\(/);
});

// ---- Chat about this (#683) -----------------------------------------------------

// The view drawn with its file state stood in: `result` as loadRepoFile left it.
const viewWith = (result, props = {}) => {
  const real = { useState: React.useState, useEffect: React.useEffect };
  React.useState = () => [result, () => {}];
  React.useEffect = () => {};
  try { return RepoFileView({ id: "o/r:docs/guide.md", commit: "abc1234def", fleet: null, ...props }); }
  finally { Object.assign(React, real); }
};
const chatButton = (tree) => walk(tree).find((e) => e.type === "button" && e.props.className === "repoChat");
const bytes = (t) => new TextEncoder().encode(t);

test("Chat about this is drawn for a text or markdown file, not while loading, on an error, or for a binary", () => {
  const on = () => {};
  const button = chatButton(viewWith({ file: { bytes: bytes("# hi") } }, { onChatAbout: on }));
  assert.ok(button, "markdown");
  assert.equal(button.props.children, "Chat about this");
  assert.ok(chatButton(viewWith({ file: { bytes: bytes("let a = 1;") } }, { id: "o/r:a.js", onChatAbout: on })), "text");
  assert.equal(chatButton(viewWith({ loading: true }, { onChatAbout: on })), undefined, "loading");
  assert.equal(chatButton(viewWith({ error: "not found" }, { onChatAbout: on })), undefined, "error");
  assert.equal(chatButton(viewWith({ file: { bytes: new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0, 1, 2]) } }, { id: "o/r:logo.png", onChatAbout: on })),
    undefined, "binary");
});

test("Chat about this hands { repo, path, commit } to onChatAbout", () => {
  const got = [];
  chatButton(viewWith({ file: { bytes: bytes("# hi") } }, { onChatAbout: (f) => got.push(f) })).props.onClick();
  assert.deepEqual(got, [{ repo: "o/r", path: "docs/guide.md", commit: "abc1234def" }]);
});

test("Chat about this sits over the view: the path line and the body draw the same with and without it", () => {
  const result = { file: { bytes: bytes("let a = 1;\n") } };
  const without = renderToStaticMarkup(viewWith(result, { id: "o/r:a.js" }));
  const withIt = renderToStaticMarkup(viewWith(result, { id: "o/r:a.js", onChatAbout: () => {} }));
  const button = withIt.match(/<button[^>]*class="repoChat"[^>]*>Chat about this<\/button>/);
  assert.ok(button, "drawn");
  assert.equal(withIt.replace(button[0], ""), without, "nothing else in the view changes");
  assert.ok(withIt.indexOf(button[0]) > withIt.indexOf("</h2>") && withIt.indexOf(button[0]) > withIt.indexOf("repoCode"),
    "after the path line and the body, so neither is pushed down");
  const css = src("renderer/styles.css");
  assert.match(css, /\.fleet-root\.repoFileView \{[^}]*position: relative/);
  assert.match(css, /\.fleet-root \.repoChat \{[^}]*position: absolute/);
  assert.match(css, /\.fleet-root\.repoFileView h2 \{[^}]*padding-right/, "the path line keeps room for it either way");
  assert.match(css, /@media \(prefers-color-scheme: light\) \{\s*\.fleet-root \.repoChat \{/, "a light-scheme rule");
});
