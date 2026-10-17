// Grafana public dashboards under Explorer > Analytics (issue #332):
// src/grafana-config.js (the config keys, the frame URL, the page's CSP, the
// probe, which URLs may leave the app) and the explorer rows and the view.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const net = require("node:net");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");

const {
  parseGrafana, dashboardUrl, cspWithFrame, framePage, pageToLoad, probe, isExternalUrl, loadGrafana,
} = require("../src/grafana-config");
const { Explorer } = require("../dist/explorer.cjs");
const { GrafanaDashboard, FRAME_SANDBOX } = require("../dist/grafana.cjs");

const ROOT = path.join(__dirname, "..");
const src = (...p) => fs.readFileSync(path.join(ROOT, "src", ...p), "utf8");
const BASE = "http://grafana.example:3000";
const BUILD_GATE = "/public-dashboards/13f5cabf8653434ebebb72eac1e6b1c5";
const draw = (el) => renderToStaticMarkup(el);

// --- the config keys ---------------------------------------------------------

test("a valid Grafana block gives one dashboard per entry, with its frame URL", () => {
  const g = parseGrafana({ grafana_url: `${BASE}/`, grafana_dashboards: [{ label: "Build gate", path: BUILD_GATE }] });
  assert.equal(g.error, null);
  assert.equal(g.origin, BASE);
  assert.deepEqual(g.dashboards, [{ label: "Build gate", path: BUILD_GATE, url: `${BASE}${BUILD_GATE}?theme=dark&kiosk` }]);
});

test("the checked-in example has grafana_url and Build gate as its first dashboard, and still loads for sign-in", () => {
  const file = path.join(ROOT, "agent-army.config.example.json");
  const raw = JSON.parse(fs.readFileSync(file, "utf8"));
  assert.equal(raw.grafana_url, BASE);
  assert.deepEqual(raw.grafana_dashboards[0], { label: "Build gate", path: BUILD_GATE, builds: true });
  const g = loadGrafana({ file });
  assert.equal(g.error, null);
  assert.equal(g.dashboards[0].url, `${BASE}${BUILD_GATE}?theme=dark&kiosk`);
});

test("missing Grafana keys mean no rows and no error", () => {
  for (const cfg of [{}, { client_id: "x" }, { grafana_url: "" }, null]) {
    assert.deepEqual(parseGrafana(cfg), { base: null, origin: null, dashboards: [], error: null });
  }
  assert.deepEqual(loadGrafana({ file: path.join(os.tmpdir(), "no-such-agent-army.config.json") }).dashboards, []);
  // a URL with no list yet is fine too: nothing to show
  assert.deepEqual(parseGrafana({ grafana_url: BASE }).dashboards, []);
});

test("a malformed grafana_url or list is one error and no rows", () => {
  const cases = [
    [{ grafana_dashboards: [{ label: "a", path: "/p" }] }, /grafana_url is missing/],
    [{ grafana_url: "not a url", grafana_dashboards: [] }, /not a URL/],
    [{ grafana_url: "file:///etc", grafana_dashboards: [] }, /http or https/],
    [{ grafana_url: `${BASE}?x=1`, grafana_dashboards: [] }, /no query/],
    [{ grafana_url: "http://admin:pw@grafana.example:3000", grafana_dashboards: [] }, /no credentials/],
    [{ grafana_url: BASE, grafana_dashboards: { label: "a" } }, /must be a list/],
  ];
  for (const [cfg, re] of cases) {
    const g = parseGrafana(cfg);
    assert.match(g.error, re);
    assert.deepEqual(g.dashboards, []);
  }
});

test("a malformed entry is its own error, and the good ones beside it still show", () => {
  const g = parseGrafana({ grafana_url: BASE, grafana_dashboards: [
    { label: "Build gate", path: BUILD_GATE }, "x", { path: "/p" }, { label: "abs", path: "http://evil/x" },
    { label: "proto", path: "//evil.example/x" }, { label: "frag", path: "/p#x" },
  ] });
  assert.equal(g.error, null);
  assert.ok(g.dashboards[0].url);
  assert.deepEqual(g.dashboards.slice(1).map((d) => [Boolean(d.url), Boolean(d.error)]),
    [[false, true], [false, true], [false, true], [false, true], [false, true]]);
  assert.match(g.dashboards[3].error, /^abs: path must start/);
});

test("a config file that sign-in refuses is a Grafana error too, not a crash", () => {
  const read = () => JSON.stringify({ password: "x", grafana_url: BASE });
  assert.match(loadGrafana({ file: "/x/agent-army.config.json", readFileSync: read }).error, /password/);
  assert.match(loadGrafana({ file: "/x/agent-army.config.json", readFileSync: () => "{" }).error, /not valid JSON/);
});

// --- the frame URL ------------------------------------------------------------

test("the frame URL is grafana_url + path with kiosk and theme=dark, and sets no refresh", () => {
  assert.equal(dashboardUrl(BASE, BUILD_GATE), `${BASE}${BUILD_GATE}?theme=dark&kiosk`);
  assert.equal(dashboardUrl(`${BASE}/grafana/`, "/public-dashboards/abc"),
    `${BASE}/grafana/public-dashboards/abc?theme=dark&kiosk`);
  // a query the user put in the path is kept, and ours wins for theme/kiosk
  const u = dashboardUrl(BASE, "/public-dashboards/abc?from=now-6h&theme=light");
  assert.equal(u, `${BASE}/public-dashboards/abc?from=now-6h&theme=dark&kiosk`);
  assert.doesNotMatch(u, /refresh/);
});

// --- the CSP --------------------------------------------------------------------

const BASE_CSP = "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:";

test("frame-src allows exactly the configured origin, and nothing else in the policy changes", () => {
  const csp = cspWithFrame(BASE_CSP, `${BASE}/some/path`);
  assert.equal(csp, `${BASE_CSP}; frame-src ${BASE}`);
  assert.equal(cspWithFrame(BASE_CSP, null), BASE_CSP);
  // an old frame-src is replaced, not added to
  assert.equal(cspWithFrame(`${BASE_CSP}; frame-src https://old`, BASE), `${BASE_CSP}; frame-src ${BASE}`);
});

test("the page main loads carries that policy, keeps the checked-in one's directives and finds its assets", () => {
  const rendererDir = path.join(ROOT, "src", "renderer");
  const html = fs.readFileSync(path.join(rendererDir, "index.html"), "utf8");
  const policy = (h) => h.match(/http-equiv="Content-Security-Policy" content="([^"]*)"/)[1];
  // the checked-in page is untouched: no frame is allowed without a config
  assert.equal(policy(html), BASE_CSP);
  const out = fs.mkdtempSync(path.join(os.tmpdir(), "grafana-page-"));
  const page = pageToLoad({ rendererDir, origin: BASE, outDir: out });
  assert.equal(page, path.join(out, "index.html"));
  const written = fs.readFileSync(page, "utf8");
  const p = policy(written);
  assert.equal(p, `${BASE_CSP}; frame-src ${BASE}`);
  assert.doesNotMatch(p.replace("style-src 'self' 'unsafe-inline'", ""), /\*|unsafe-/, "no wildcard or unsafe-* was added");
  assert.match(written, new RegExp(`<base href="${require("node:url").pathToFileURL(rendererDir).href}/">`));
  assert.ok(written.includes('<script src="../../dist/renderer.js">'), "the asset links are unchanged, resolved through <base>");
  // no origin: the checked-in page, and nothing written
  assert.equal(pageToLoad({ rendererDir, origin: null, outDir: path.join(out, "none") }), path.join(rendererDir, "index.html"));
  assert.equal(fs.existsSync(path.join(out, "none")), false);
  assert.throws(() => framePage("<html><head></head></html>", BASE, rendererDir), /no Content-Security-Policy/);
});

test("the window keeps contextIsolation, nodeIntegration off and the sandbox", () => {
  const main = src("main.js");
  assert.match(main, /contextIsolation: true/);
  assert.match(main, /nodeIntegration: false/);
  assert.match(main, /sandbox: true/);
  assert.match(main, /pageToLoad\(\{ rendererDir: path\.join\(__dirname, "renderer"\), origin: grafanaConfig\(\)\.origin/);
});

// --- down detection ---------------------------------------------------------------

const URL1 = `${BASE}${BUILD_GATE}?theme=dark&kiosk`;

test("the probe says ok on a 2xx", async () => {
  const r = await probe(URL1, { fetchFn: async () => ({ status: 200, statusText: "OK" }) });
  assert.deepEqual(r, { ok: true });
});

test("refused, timed out and non-2xx are all down, and the error names the URL", async () => {
  const refused = async () => { const e = new TypeError("fetch failed"); e.cause = { code: "ECONNREFUSED" }; throw e; };
  const timeout = async () => { const e = new Error("timed out"); e.name = "TimeoutError"; throw e; };
  const notFound = async () => ({ status: 404, statusText: "Not Found" });
  const r1 = await probe(URL1, { fetchFn: refused });
  assert.equal(r1.ok, false); assert.ok(r1.error.includes(URL1)); assert.match(r1.error, /ECONNREFUSED/);
  const r2 = await probe(URL1, { fetchFn: timeout, timeoutMs: 5000 });
  assert.equal(r2.ok, false); assert.ok(r2.error.includes(URL1)); assert.match(r2.error, /within 5 s/);
  const r3 = await probe(URL1, { fetchFn: notFound });
  assert.equal(r3.ok, false); assert.ok(r3.error.includes(URL1)); assert.match(r3.error, /404/);
});

test("the probe against a real closed port reports it down", async () => {
  const port = await new Promise((resolve) => {
    const s = net.createServer().listen(0, "127.0.0.1", () => { const p = s.address().port; s.close(() => resolve(p)); });
  });
  const url = `http://127.0.0.1:${port}/public-dashboards/x`;
  const r = await probe(url, { timeoutMs: 3000 });
  assert.equal(r.ok, false);
  assert.ok(r.error.includes(url));
});

test("main probes by index, so the renderer never names a URL", () => {
  const preload = src("preload.js");
  assert.match(preload, /grafanaProbe: \(index\) => ipcRenderer\.invoke\("grafana:probe", Number\(index\)\)/);
  assert.match(src("main.js"), /ipcMain\.handle\("grafana:probe", async \(_e, index\) => \{\n  const d = grafanaConfig\(\)\.dashboards\[Number\(index\)\]/);
});

// --- leaving the app (finding security-1-1) -----------------------------------------

test("only http and https URLs are handed to the system browser", () => {
  for (const ok of ["http://grafana.example:3000/d/x", "https://github.com/codefrak-oss/frakworks/issues/1"]) {
    assert.equal(isExternalUrl(ok), true, ok);
  }
  for (const bad of ["file:///etc/passwd", "smb://host/share", "javascript:alert(1)", "vscode://x", "ms-settings:", "", null, "not a url"]) {
    assert.equal(isExternalUrl(bad), false, String(bad));
  }
  const main = src("main.js");
  assert.match(main, /setWindowOpenHandler\(\(\{ url \}\) => \{ openOutside\(url\); return \{ action: "deny" \}; \}\)/);
  assert.match(main, /on\("will-navigate", \(e, url\) => \{ e\.preventDefault\(\); openOutside\(url\); \}\)/);
  assert.match(main, /const openOutside = \(url\) => \{\n  if \(isExternalUrl\(url\)\) shell\.openExternal\(url\);/);
  // the only openExternal (the Keycloak sign-in's went with the Chats tab, #634)
  assert.equal([...main.matchAll(/shell\.openExternal\(/g)].length, 1);
});

// --- the rows and the view ---------------------------------------------------------

const GRAFANA = { error: null, dashboards: [
  { label: "Build gate", url: URL1, error: null },
  { label: null, url: null, error: "grafana_dashboards[1] has no label" },
] };

test("Analytics shows one row per dashboard after Jev decisions, Doctor (#706) and Friction notes (#1046), Refactoring (#1781) and SSH verdicts (#2439), and a malformed one shows its error", () => {
  const html = draw(React.createElement(Explorer, { sel: { kind: "grafana", id: 0 }, onSelect: () => {}, grafana: GRAFANA }));
  assert.match(html, />Friction notes<\/span><\/div><div class="exRow"[^>]*><span class="exChev"><\/span><span class="exLabel">Refactoring<\/span><\/div><div class="exRow"[^>]*><span class="exChev"><\/span><span class="exLabel">SSH verdicts<\/span><\/div><div class="exRow on"[^>]*><span class="exChev"><\/span><span class="exLabel">Build gate</);
  assert.ok(html.includes("Grafana: grafana_dashboards[1] has no label"));
  // The AI Accounts branch (#1934) sits above them, its two pages nested one level under it.
  assert.match(html, /<span class="exLabel">AI Accounts<\/span><\/div><div class="exRow" style="padding-left:36px"[^>]*><span class="exChev"><\/span><span class="exLabel">Account balances<\/span><\/div><div class="exRow" style="padding-left:36px"[^>]*><span class="exChev"><\/span><span class="exLabel">Account consumption<[\s\S]*Build gate/);
  // Builds (#1935) sits after the Grafana rows, the branch's last; Utils (#1947) follows Analytics, then Tickets.
  assert.match(html, /Grafana: grafana_dashboards\[1\] has no label<\/div><div class="exRow"[^>]*><span class="exChev"><\/span><span class="exLabel">Builds<\/span><\/div><div class="exRow exBranch"[^>]*><span class="exChev">.<\/span><span class="exLabel">Utils<\/span><\/div><div class="exRow exBranch"[^>]*><span class="exChev">.<\/span><span class="exLabel">HITLP Requests</);
  const none = draw(React.createElement(Explorer, { sel: { kind: "jev" }, onSelect: () => {} }));
  assert.doesNotMatch(none, /Grafana|Build gate/);
  const broken = draw(React.createElement(Explorer, { sel: { kind: "jev" }, onSelect: () => {}, grafana: { error: "grafana_url is not a URL: \"x\"", dashboards: [] } }));
  assert.ok(broken.includes("Grafana: grafana_url is not a URL"));
});

test("an answered probe shows the frame: the built URL, sandboxed, filling the work area", () => {
  const html = draw(React.createElement(GrafanaDashboard, { dashboard: GRAFANA.dashboards[0], check: { ok: true } }));
  assert.match(html, /^<iframe class="grafana" title="Build gate"/);
  assert.ok(html.includes(`src="${URL1.replace(/&/g, "&amp;")}"`));
  assert.ok(html.includes(`sandbox="${FRAME_SANDBOX}"`));
  assert.doesNotMatch(FRAME_SANDBOX, /allow-top-navigation|allow-forms|allow-downloads/);
  const css = src("renderer", "styles.css");
  assert.match(css, /\.work > iframe\.grafana \{ display: block; width: 100%; height: 100%; border: 0;/);
});

test("a down Grafana is the usual error block naming the URL, not a blank frame", () => {
  const error = `Grafana is unreachable at ${URL1}: ECONNREFUSED`;
  const html = draw(React.createElement(GrafanaDashboard, { dashboard: GRAFANA.dashboards[0], check: { ok: false, error } }));
  assert.ok(html.includes(`<div class="err">${error.replace(/&/g, "&amp;")}</div>`));
  assert.doesNotMatch(html, /<iframe/);
  const checking = draw(React.createElement(GrafanaDashboard, { dashboard: GRAFANA.dashboards[0], check: null }));
  assert.doesNotMatch(checking, /<iframe/);
  assert.match(checking, /checking Grafana/);
  const bad = draw(React.createElement(GrafanaDashboard, { dashboard: GRAFANA.dashboards[1], check: null }));
  assert.ok(bad.includes('<div class="err">grafana_dashboards[1] has no label</div>'));
});

// --- the builds strip (#1389) -------------------------------------------------

test("`builds: true` on an entry is kept, anything else is dropped", () => {
  const g = parseGrafana({ grafana_url: BASE, grafana_dashboards: [
    { label: "Build gate", path: BUILD_GATE, builds: true }, { label: "Other", path: "/p", builds: "yes" }] });
  assert.equal(g.dashboards[0].builds, true);
  assert.equal("builds" in g.dashboards[1], false);
});

test("the strip is drawn above the frame only for a dashboard that asks for it", () => {
  const d = { label: "Build gate", url: `${BASE}${BUILD_GATE}` };
  const data = { builds: [], timeout: "PT3H" };
  const plain = draw(React.createElement(GrafanaDashboard, { dashboard: d, check: { ok: true }, builds: data }));
  assert.doesNotMatch(plain, /build-strip/);
  const withStrip = draw(React.createElement(GrafanaDashboard, { dashboard: { ...d, builds: true }, check: { ok: true }, builds: data }));
  assert.match(withStrip, /class="build-strip".*<iframe/s);
  assert.match(withStrip, /no builds running/);
});

test("the MCP server test is main's too: the renderer names a repo and a server, never a command or a secret (#1801)", () => {
  const preload = src("preload.js");
  assert.match(preload, /testMcpServer: \(repo, name\) => ipcRenderer\.invoke\("engine:mcp-server-test", repo \? String\(repo\) : null, name \? String\(name\) : null\)/);
  // The Needs view's HITLP decision (#2487) rides the same bridge to the HITLP server: a task id, an outcome, the answer, a reason and the digest shown.
  assert.match(preload, /hitlpSurfaceDecide: \(taskId, outcome, answer, reason, payloadDigest\) => ipcRenderer\.invoke\("hitlp:surface-decide"/);
  // The ticket detail's inline task (#100070) reads one task by id; main answers its row, never the credential.
  assert.match(preload, /hitlpSurfaceGet: \(taskId\) => ipcRenderer\.invoke\("hitlp:surface-get", String\(taskId == null \? "" : taskId\)\)/);
  assert.match(src("main.js"), /ipcMain\.handle\("hitlp:surface-get", async \(_e, taskId\) => \{\n  const r = await fetchHitlpTask\(/);
  // #100091: the update preview modal's four channels; none takes an argument from the renderer
  assert.match(preload, /updatePreview: \(\) => ipcRenderer\.invoke\("app:update-preview"\)/);
  assert.match(preload, /snapshot: \(\) => ipcRenderer\.invoke\("app:snapshot"\)/);
  assert.match(preload, /engineUpdatePreview: \(\) => ipcRenderer\.invoke\("engine:update-preview"\)/);
  assert.match(preload, /updateEngine: \(\) => ipcRenderer\.invoke\("engine:update-run"\)/);
  // #2206: Sign in to the engine, three channels; the token goes inward only
  assert.match(preload, /signIn: \(token\) => ipcRenderer\.invoke\("engine:sign-in", String\(token == null \? "" : token\)\)/);
  assert.match(preload, /signOut: \(\) => ipcRenderer\.invoke\("engine:sign-out"\)/);
  assert.match(preload, /signInStatus: \(\) => ipcRenderer\.invoke\("engine:sign-in-status"\)/);
  // Config > frakworks > "Let First mate screenshot this app" (#100105): a read and one boolean write, main's file.
  assert.match(preload, /screenshotSetting: \(\) => ipcRenderer\.invoke\("screenshot:setting"\)/);
  assert.match(preload, /setScreenshotSetting: \(allowed\) => ipcRenderer\.invoke\("screenshot:set-setting", allowed === true\)/);
  const main = src("main.js");
  assert.match(main, /ipcMain\.handle\("engine:mcp-server-test", async \(_e, repo, name\) => \{\n  const config = await fetchRepoMcpConfig\(repo\);/);
  assert.doesNotMatch(preload, /mcp-servers\/config|fetchRepoMcpConfig/);
});

test("a post-deploy wait holds its bar, so no meter ticks while the deploy or the next check is awaited", () => {
  const { progressOf } = require("../dist/issues.cjs");
  const flow = { flow: "chore", outcome: "started", ended: false, stage: "call-post-deploy-check", step: "every 12 h",
    stageStartedAt: "2026-10-13T10:00:00Z", stageEstimateMs: 60000, stageRound: 1 };
  assert.equal(progressOf({ kind: "chore", flow }, Date.parse("2026-10-13T11:00:00Z")).hold, true);
});
