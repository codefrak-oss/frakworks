// The repo switcher in the title strip (#577): its label, its dropdown (the
// target repos with needs counts, All repos, Manage repos…), and the
// `owner/name#n` ticket refs every view now uses.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { RepoSwitcher, parseTicketRef, ticketId, ticketLabel, titleFor } = require("../dist/repo-switcher.cjs");
const { TitleBar } = require("../dist/title-bar.cjs");

const selection = { repo: "o/a", repos: ["o/a", "o/b"], needs: { "o/a": 2 }, error: null };
const render = (el, props) => renderToStaticMarkup(React.createElement(el, props));

test("closed, the switcher reads [repo icon] owner/name ▾ or All repos, with no badge", () => {
  const one = render(RepoSwitcher, { selection, open: false });
  assert.match(one, /<svg class="repoIcon"[^>]*>.*?<\/svg>o\/a ▾/);
  assert.doesNotMatch(one, /▣/);
  assert.doesNotMatch(one, /repoBadge|repoMenu"/);
  assert.match(render(RepoSwitcher, { selection: { ...selection, repo: null }, open: false }), />All repos ▾</);
});

test("open, the dropdown lists every target repo with its needs count, then All repos, then Manage repos…", () => {
  const html = render(RepoSwitcher, { selection, open: true });
  const names = [...html.matchAll(/class="repoMenuName">(?:<svg[^]*?<\/svg>)?([^<]*(?:<!-- -->[^<]*)*)</g)].map((m) => m[1].replace(/<!-- -->/g, ""));
  assert.deepEqual(names, ["o/a", "o/b", "All repos", "Manage repos…"]);
  assert.equal((html.match(/class="repoMenuName"><svg class="repoIcon"/g) || []).length, 2);
  assert.doesNotMatch(html, /▣/);
  assert.match(html, /class="repoBadge some"[^>]*>2</);
  assert.match(html, /class="repoBadge"[^>]*>0</);
  assert.match(html, /aria-checked="true" class="repoMenuItem on"/);
});

test("the title strip holds the switcher and the notice, with no titleBarTitle", () => {
  const html = render(TitleBar, { selection, open: false });
  assert.match(html, /^<div class="titleBar"><div class="repoSwitcher">/);
  assert.doesNotMatch(html, /titleBarTitle/);
  assert.match(render(TitleBar, { selection: { repos: [] }, notice: "o/x is no longer a target repo" }),
    /<\/div><span class="titleBarNotice"/);
  assert.equal(titleFor(null), "All repos");
});

test("ticket refs: owner/name#n, or a bare number in the fallback repo", () => {
  assert.equal(ticketId("o/r", 5), "o/r#5");
  assert.deepEqual(parseTicketRef("o/r#5"), { repo: "o/r", number: 5 });
  assert.deepEqual(parseTicketRef("12", "o/s"), { repo: "o/s", number: 12 });
  assert.deepEqual(parseTicketRef(12), { repo: null, number: 12 });
  for (const bad of [null, "", "0", "-3", "x", "o/r#x"]) assert.equal(parseTicketRef(bad, "o/s"), null, String(bad));
  assert.equal(ticketLabel("o/r", 5, "o/r"), "#5");
  assert.equal(ticketLabel("o/r", 5, null), "o/r#5");
  assert.equal(ticketLabel("o/r", 5, "o/s"), "o/r#5");
});

test("Repo names (#2429): long by default, short when chosen, long for a shared name; All repos and Manage unchanged", () => {
  const names = (html) => [...html.matchAll(/class="repoMenuName">(?:<svg[^]*?<\/svg>)?([^<]*(?:<!-- -->[^<]*)*)</g)].map((m) => m[1].replace(/<!-- -->/g, ""));
  const sel = { repo: "o/a", repos: ["o/a", "o/b", "p/b"], needs: {}, error: null };
  assert.deepEqual(names(render(RepoSwitcher, { selection: sel, open: true })), ["o/a", "o/b", "p/b", "All repos", "Manage repos…"]);
  const short = render(RepoSwitcher, { selection: sel, open: true, nameMode: "short" });
  assert.deepEqual(names(short), ["a", "o/b", "p/b", "All repos", "Manage repos…"]);
  assert.match(short, /<\/svg>a ▾/);
  assert.match(render(RepoSwitcher, { selection: sel, open: false, nameMode: "long" }), /<\/svg>o\/a ▾/);
});
