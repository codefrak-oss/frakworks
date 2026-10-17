// The app's config file (src/answer-config.js): public values only, and a
// file that carries a credential is refused whole. Stage 4's Keycloak and
// tickets-MCP checks went with the Chats tab (#634).
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const { readConfigFile, FORBIDDEN_KEYS, CONFIG_BASENAME, configPath } = require("../src/answer-config");

const EXAMPLE = path.join(__dirname, "..", "agent-army.config.example.json");

const fakeFile = (contents) => (file, _enc) => {
  if (contents === null) { const e = new Error("ENOENT"); e.code = "ENOENT"; throw e; }
  return typeof contents === "string" ? contents : JSON.stringify(contents);
};

test("the checked-in example is readable and carries no credential", () => {
  const r = readConfigFile(EXAMPLE);
  assert.equal(r.error, undefined, r.error);
  for (const k of FORBIDDEN_KEYS) assert.equal(k in r.value, false, `${k} is not in the example`);
  for (const k of ["issuer", "client_id", "redirect_port", "tickets_url", "scope"]) {
    assert.equal(k in r.value, false, `${k} went with stage 4 (#634)`);
  }
});

test("a missing file is an empty config, not an error; a non-object is an error", () => {
  assert.deepEqual(readConfigFile("cfg.json", { readFileSync: fakeFile(null) }), { value: {} });
  assert.match(readConfigFile("cfg.json", { readFileSync: fakeFile("[1]") }).error, /JSON object/);
  assert.match(readConfigFile("cfg.json", { readFileSync: fakeFile("{") }).error, /not valid JSON/);
  assert.equal(configPath("/u"), path.join("/u", CONFIG_BASENAME));
});

test("a config file carrying a credential is refused whole", () => {
  for (const key of ["client_secret", "pat", "refresh_token"]) {
    const r = readConfigFile("cfg.json", {
      readFileSync: fakeFile({ grafana_url: "https://g", [key]: "s3cret" }),
    });
    assert.match(r.error, new RegExp(key));
    assert.match(r.error, /no credential belongs in this file/);
  }
});

test("no source file in the tree hard-codes a secret-looking value", () => {
  const dir = path.join(__dirname, "..", "src");
  const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]);
  for (const file of walk(dir)) {
    const text = fs.readFileSync(file, "utf8");
    assert.ok(!/client_secret\s*[:=]\s*["'][^"']+["']/.test(text), `${file} sets a client secret`);
    assert.ok(!/gh[ps]_[A-Za-z0-9]{20,}/.test(text), `${file} contains a PAT-shaped string`);
  }
});
