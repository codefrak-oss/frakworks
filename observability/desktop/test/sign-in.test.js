// Config > frakworks > Sign in to the engine (#2206): src/renderer/sign-in.jsx
// drawn signed out, signed in, and with the engine's refusal; it takes no
// token prop and renders none.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { SignInPanel } = require("../dist/sign-in.cjs");

const draw = (props) => renderToStaticMarkup(React.createElement(SignInPanel, props));

test("signed out: a password field for the token and a Sign in button, disabled until something is typed", () => {
  const html = draw({ result: { signedIn: false, login: null, name: null, error: null }, onSignIn: () => {}, onSignOut: () => {} });
  assert.match(html, /Sign in to the engine/);
  assert.match(html, /<input class="acInput signInToken" type="password" autoComplete="off"[^>]*aria-label="engine token"[^>]*placeholder="frk_…"/);
  assert.match(html, /<button type="submit" class="acBtn" disabled="">Sign in<\/button>/);
  assert.doesNotMatch(html, /Sign out/);
  assert.doesNotMatch(html, /class="err"/);
});

test("signed in: the login and the token's name, and Sign out", () => {
  const html = draw({ result: { signedIn: true, login: "someone", name: "desktop on the laptop", error: null }, onSignIn: () => {}, onSignOut: () => {} });
  assert.match(html, /Signed in as <b>@someone<\/b> with token <b>desktop on the laptop<\/b>/);
  assert.match(html, /<button type="button" class="acBtn">Sign out<\/button>/);
  assert.doesNotMatch(html, /type="password"/);
});

test("a 401 or 403 from the status read shows the engine's text under the signed-in line, and says the app keeps working", () => {
  const html = draw({ result: { signedIn: true, login: "someone", name: "n", error: "engine answered 401 for GET /whoami: no such token" }, onSignIn: () => {}, onSignOut: () => {} });
  assert.match(html, /<div class="err">The engine refused the token: engine answered 401 for GET \/whoami: no such token\. The app keeps working with the login header\.<\/div>/);
  assert.match(html, /Sign out/);
  // signed out with an error (a saved token that could not be decrypted): the text alone
  const out = draw({ result: { signedIn: false, login: null, name: null, error: "the saved token could not be decrypted (bad key); sign in again" } });
  assert.match(out, /<div class="err">the saved token could not be decrypted \(bad key\); sign in again<\/div>/);
  assert.match(out, /type="password"/);
});

test("before main's first answer it says so", () => {
  assert.match(draw({ result: { loading: true } }), /asking main…/);
});

test("the component takes no token prop and renders none", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "sign-in.jsx"), "utf8");
  assert.match(src, /export const SignInPanel = \(\{ result, onSignIn, onSignOut, onRefresh, web = false, title = "Sign in to the engine", hint = null,\s*action = "Sign in", signedInText = "Signed in as", urlField = null, placeholder = "frk_…" \}\) =>/);
  assert.doesNotMatch(src, /result\.token|r\.token|props\.token/);
  // the field is cleared after every submit, success or not
  assert.match(src, /setBusy\(false\);\s*setText\(""\);/);
  const token = "frk_test_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
  const html = draw({ result: { signedIn: true, login: "someone", name: "n", error: null, token } });
  assert.ok(!html.includes(token), "a token smuggled into result is not drawn");
});
