// Pair a phone in the web version (src/renderer/pair-phone.jsx, the bridge's
// `pairing` in src/web/fleet-web.js, src/qr.js): no public URL means no QR and
// no mint; a mint is a first-mate token for the signed-in login, the QR holds
// {url, token}, and Revoke is DELETE /tokens/{id}.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { PairView } = require("../dist/pair-phone.cjs");
const { qrSvg, pairingPayload } = require("../src/qr");
const { createWebFleet, TOKEN_KEY, memoryStorage } = require("../src/web/fleet-web");

const draw = (props) => renderToStaticMarkup(React.createElement(PairView, { tokens: { tokens: [], error: null }, onMint() {}, onRevoke() {}, ...props }));
const quietStream = () => ({ setSelected() {}, snapshotted: () => ({ ok: true }), streamState: () => ({ state: "down" }), subscribe: () => ({ ok: true }), unsubscribe: () => ({ ok: true }) });

function withFetch(answer, fn) {
  const calls = [];
  const was = globalThis.fetch;
  globalThis.fetch = async (url, init = {}) => {
    calls.push({ url: String(url), method: init.method || "GET", headers: init.headers || {}, body: init.body ? JSON.parse(init.body) : null });
    const { status = 200, body = {} } = answer(String(url), init) || {};
    return { ok: status >= 200 && status < 300, status, text: async () => (typeof body === "string" ? body : JSON.stringify(body)) };
  };
  return Promise.resolve(fn(calls)).finally(() => { globalThis.fetch = was; });
}

const signedIn = () => {
  const storage = memoryStorage();
  storage.setItem(TOKEN_KEY, "frk_page");
  return createWebFleet({ storage, stream: quietStream() }).pairing;
};

test("no public URL: the page says so, with the engine's note, and shows no QR and no mint button", () => {
  const html = draw({ url: { publicUrl: null, note: "no public URL is set; set it with PUT /settings/public-url" } });
  assert.match(html, /no public URL set/);
  assert.match(html, /set it with PUT \/settings\/public-url/);
  assert.doesNotMatch(html, /<svg/);
  assert.doesNotMatch(html, /Mint a phone token/);
});

test("a public URL and no token yet: the mint button, no QR", () => {
  const html = draw({ url: { publicUrl: "https://engine.example.net" } });
  assert.match(html, /Mint a phone token/);
  assert.doesNotMatch(html, /<svg/);
});

test("minted: a QR of {url, token}, the token once, and the phone tokens with Revoke", () => {
  const html = draw({ url: { publicUrl: "https://engine.example.net" }, minted: { id: "t1", token: "frk_secret" },
    tokens: { tokens: [{ id: "t1", name: "iPhone 2026-10-11", prefix: "frk_se", login: "someone" }], error: null } });
  const q = qrSvg(pairingPayload("https://engine.example.net", "frk_secret"));
  assert.ok(html.includes(`<path d="${q.path}"`), "the QR drawn is the payload's");
  assert.match(html, /frk_secret/);
  assert.match(html, /iPhone 2026-10-11/);
  assert.match(html, /Revoke/);
  assert.equal(pairingPayload("https://e", "t"), JSON.stringify({ url: "https://e", token: "t" }));
});

test("the QR encoder yields a square symbol with finder patterns", () => {
  const q = qrSvg(pairingPayload("https://engine.example.net", "frk_" + "x".repeat(40)));
  assert.equal(q.size, q.modules + 8);
  for (const [r, c] of [[0, 0], [0, q.modules - 7], [q.modules - 7, 0]]) {
    assert.ok(q.isDark(r, c) && q.isDark(r + 3, c + 3) && !q.isDark(r + 1, c + 1), `finder at ${r},${c}`);
  }
});

test("the bridge reads the public URL with the page's bearer", () => withFetch((url) => {
  if (url.endsWith("/settings/public-url")) return { body: { publicUrl: null, note: "unset" } };
}, async (calls) => {
  const r = await signedIn().publicUrl();
  assert.deepEqual(r, { publicUrl: null, note: "unset" });
  assert.equal(calls[0].url, "/settings/public-url");
  assert.equal(calls[0].headers.authorization, "Bearer frk_page");
}));

test("a mint is POST /tokens with purpose first-mate for the signed-in login", () => withFetch((url, init) => {
  if (url.endsWith("/whoami")) return { body: { login: "someone", tokenId: "p", name: "page" } };
  if (url.endsWith("/tokens") && init.method === "POST") return { status: 201, body: { id: "t1", token: "frk_new", login: "someone", name: "iPhone x", prefix: "frk_ne", purpose: "first-mate" } };
}, async (calls) => {
  const r = await signedIn().mint("iPhone x");
  assert.deepEqual(r, { id: "t1", token: "frk_new", prefix: "frk_ne", name: "iPhone x" });
  const post = calls.find((c) => c.method === "POST");
  assert.equal(post.url, "/tokens");
  assert.deepEqual(post.body, { login: "someone", name: "iPhone x", purpose: "first-mate" });
  assert.equal(post.headers.authorization, "Bearer frk_page");
}));

test("the list keeps live first-mate tokens, and Revoke is DELETE /tokens/{id}", () => withFetch((url, init) => {
  if (url.endsWith("/tokens") && (init.method || "GET") === "GET") return { body: [
    { id: "a", purpose: "first-mate", revokedAt: null }, { id: "b", purpose: null, revokedAt: null }, { id: "c", purpose: "first-mate", revokedAt: "2026-10-01T00:00:00Z" }] };
  if (init.method === "DELETE") return { status: 204, body: "" };
}, async (calls) => {
  const p = signedIn();
  assert.deepEqual((await p.tokens()).tokens.map((t) => t.id), ["a"]);
  assert.deepEqual(await p.revoke("a"), { ok: true });
  const del = calls.find((c) => c.method === "DELETE");
  assert.equal(del.url, "/tokens/a");
}));
