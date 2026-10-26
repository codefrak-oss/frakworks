// Issue #100140: First mate's header actions in rows by purpose, and repo labels that agree.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { FirstMate, clusterRepoLabels, newConversationRepoText, NEW_CHAT_NO_REPO } = require("../dist/first-mate.cjs");

const draw = (el) => renderToStaticMarkup(el);
const snap = (over) => ({ history: [], busy: false, ...over });
const css = fs.readFileSync(path.join(__dirname, "../src/renderer/styles.css"), "utf8");

test("the switcher's repo names the new chat, whatever this chat's repo is", () => {
  const l = clusterRepoLabels({ repo: "o/b", repos: ["o/a", "o/b"] }, { chat_repo_on: true, chat_repo: "o/a" });
  assert.equal(l.newChatSuffix, " · in o/b");
  assert.equal(l.chat, "o/a");
  assert.equal(l.nextSticky, false);
});

test("All repos with a repo in use: the new chat starts in it, tagged last used, never \"no repo\"", () => {
  for (const sticky of [true, false]) {
    const l = clusterRepoLabels({ repo: null, repos: ["o/a"] }, { chat_repo_on: true, chat_repo: "o/a", chat_repo_sticky: sticky });
    assert.equal(l.newChatSuffix, " · in o/a (last used)");
    assert.equal(l.chatSticky, sticky);
  }
  assert.equal(clusterRepoLabels({ repo: null, repos: null }, { chat_repo_on: true, chat_repo: "o/a" }).newChatSuffix, " · in o/a (last used)");
});

test("All repos with no repo, or one the target repos no longer list: no repo", () => {
  assert.equal(clusterRepoLabels({ repo: null, repos: ["o/a"] }, { chat_repo_on: true, chat_repo: null }).newChatSuffix, NEW_CHAT_NO_REPO);
  assert.equal(clusterRepoLabels({ repo: null, repos: ["o/b"] }, { chat_repo_on: true, chat_repo: "o/a" }).newChatSuffix, NEW_CHAT_NO_REPO);
  assert.equal(newConversationRepoText(null, null), NEW_CHAT_NO_REPO);
});

test("with the chip off the cluster names no repo", () => {
  const html = draw(React.createElement(FirstMate, { snapshot: snap(), selection: { repo: "o/a", repos: ["o/a"] } }));
  assert.match(html, />Start new conversation<\/button>/);
  assert.doesNotMatch(html, /fmChatRepo/);
});

test("the header draws New chat then This chat rows, each button in its row", () => {
  const html = draw(React.createElement(FirstMate, { snapshot: snap({ chat_repo_on: true, chat_repo: "o/a", chat_repo_sticky: true }),
    selection: { repo: null, repos: ["o/a"] }, onChatAboutScreen: () => {} }));
  const neu = html.indexOf("fmClusterNew"), chat = html.indexOf("fmClusterChat");
  assert.ok(neu > 0 && neu < chat);
  const newRow = html.slice(neu, chat);
  assert.match(newRow, /fmChatScreen/);
  assert.match(newRow, /fmNewConversation/);
  const chatRow = html.slice(chat);
  for (const c of ["fmChatRepo", "fmSaveButton", "fmIterm"]) assert.match(chatRow, new RegExp(c));
  assert.doesNotMatch(chatRow, /fmNewConversation/);
});

test("the last used tag is inside the chip's label element", () => {
  const html = draw(React.createElement(FirstMate, { snapshot: snap({ chat_repo_on: true, chat_repo: "o/a", chat_repo_sticky: true }),
    selection: { repo: null, repos: ["o/a"] } }));
  assert.match(html, /<label class="fmChatRepo"[^>]*>(?:(?!<\/label>).)*fmChatRepoSticky(?:(?!<\/label>).)*<\/label>/s);
});

test("rows have one even gap, buttons stretch, and a narrow pane stacks them", () => {
  assert.match(css, /\.fmClusterRow \{[^}]*flex-wrap: wrap[^}]*gap: 4px 6px/);
  assert.match(css, /\.fmClusterRow > button \{[^}]*flex: 1 1 auto[^}]*margin-left: 0/);
  assert.match(css, /@container \(max-width: 240px\) \{\s*\.fmClusterRow > button \{ flex-basis: 100%; \}/);
});
