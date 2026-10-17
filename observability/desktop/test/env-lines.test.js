// Config > frakworks > .env (#912): src/env-lines.js's key rule, comparison
// and removal, and the section in src/renderer/env-lines.jsx. Every fixture
// value carries SECRET, so a value that leaks into a result or the markup is
// caught by one regex.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");

const {
  envKeys, compareEnv, removeLines, checkRemoval, projectDir, checkEnv, removeEnv, confirmText,
} = require("../src/env-lines");
const { EnvLines, unusedPhrase, removedPhrase, REVOKE_NOTE, CLEAN_HINT } = require("../dist/env-lines.cjs");

const EXAMPLE = [
  "# what a deployment sets",
  "",
  "ENGINE_DB_PASSWORD=",
  "ENGINE_SECRETS_KEY=",
  "GRAFANA_ADMIN_PASSWORD=",
  "",
].join("\n");

const noValue = (obj) => assert.doesNotMatch(JSON.stringify(obj), /SECRET_VALUE|codefrak-oss|=/);

test("no difference: nothing unused, nothing missing", () => {
  const env = "ENGINE_DB_PASSWORD=SECRET_VALUE_1\nENGINE_SECRETS_KEY=SECRET_VALUE_2\nGRAFANA_ADMIN_PASSWORD=\n";
  const r = compareEnv(env, EXAMPLE);
  assert.deepEqual(r, { unused: [], missing: [] });
  noValue(r);
});

test("one unused key", () => {
  const env = "ENGINE_DB_PASSWORD=SECRET_VALUE_1\nKEYCLOAK_SECRET=SECRET_VALUE_9\nENGINE_SECRETS_KEY=x\nGRAFANA_ADMIN_PASSWORD=y\n";
  const r = compareEnv(env, EXAMPLE);
  assert.deepEqual(r, { unused: ["KEYCLOAK_SECRET"], missing: [] });
  noValue(r);
});

test("one missing key", () => {
  const env = "ENGINE_DB_PASSWORD=SECRET_VALUE_1\nGRAFANA_ADMIN_PASSWORD=y\n";
  assert.deepEqual(compareEnv(env, EXAMPLE), { unused: [], missing: ["ENGINE_SECRETS_KEY"] });
});

test("both, each in its file's order", () => {
  const env = "B_OLD=SECRET_VALUE_b\nGRAFANA_ADMIN_PASSWORD=y\nA_OLD=SECRET_VALUE_a\n";
  const r = compareEnv(env, EXAMPLE);
  assert.deepEqual(r, { unused: ["B_OLD", "A_OLD"], missing: ["ENGINE_DB_PASSWORD", "ENGINE_SECRETS_KEY"] });
  noValue(r);
});

test("comment and blank lines are ignored, commented-out keys included", () => {
  const env = "# OLD_KEY=SECRET_VALUE_c\n\n   \n#ENGINE_DB_PASSWORD=\nENGINE_DB_PASSWORD=SECRET_VALUE_1\nENGINE_SECRETS_KEY=x\nGRAFANA_ADMIN_PASSWORD=y\n";
  assert.deepEqual(compareEnv(env, EXAMPLE), { unused: [], missing: [] });
  assert.deepEqual(envKeys("# A=1\n\nB=2\n 3C=4\nexport D=5\n"), ["B"]);
});

test("a value containing = is compared by its key only", () => {
  const env = "ENGINE_DB_PASSWORD=SECRET_VALUE=abc==\nENGINE_SECRETS_KEY=a=b\nGRAFANA_ADMIN_PASSWORD=y\nOLD_TOKEN=SECRET_VALUE=x=y\n";
  const r = compareEnv(env, EXAMPLE);
  assert.deepEqual(r, { unused: ["OLD_TOKEN"], missing: [] });
  noValue(r);
});

test("CRLF line endings are read like LF", () => {
  assert.deepEqual(envKeys("A=1\r\nB=SECRET_VALUE\r\n"), ["A", "B"]);
});

// The fixture removal: mixed content, CRLF and LF, a commented copy of a removed
// key, a duplicate, and a last line with no newline.
const MIXED = "# header comment\r\n\r\nKEEP_A=SECRET_VALUE_keep\r\nOLD_ONE=SECRET_VALUE_old\n# OLD_ONE=commented stays\n\n" +
  "KEEP_B=a=b=c\nOLD_TWO=SECRET_VALUE_two\nOLD_ONE=SECRET_VALUE_dup\n  indented=stays\nKEEP_C=last-no-newline";

test("removeLines drops exactly the named lines and keeps every other byte", () => {
  const r = removeLines(MIXED, ["OLD_ONE", "OLD_TWO"]);
  assert.equal(r.removed, 3);
  assert.equal(r.text, "# header comment\r\n\r\nKEEP_A=SECRET_VALUE_keep\r\n# OLD_ONE=commented stays\n\n" +
    "KEEP_B=a=b=c\n  indented=stays\nKEEP_C=last-no-newline");
  assert.deepEqual(removeLines(MIXED, []), { text: MIXED, removed: 0 });
});

test("checkRemoval refuses a declared key, a key not in the last check, a non-key and nothing", () => {
  const last = { unused: ["OLD_ONE", "OLD_TWO"], missing: [] };
  assert.deepEqual(checkRemoval(["OLD_ONE", "OLD_ONE"], last, ["KEEP_A"]), { keys: ["OLD_ONE"] });
  assert.match(checkRemoval(["KEEP_A"], { unused: ["KEEP_A"] }, ["KEEP_A"]).error, /declared in \.env\.example/);
  assert.match(checkRemoval(["KEEP_B"], last, []).error, /not an unused line/);
  assert.match(checkRemoval(["../x"], last, []).error, /not a key name/);
  assert.match(checkRemoval([], last, []).error, /no keys/);
  assert.match(checkRemoval(["OLD_ONE"], null, []).error, /check \.env first/);
});

const tmpProject = (envText, exampleText = "KEEP_A=\nKEEP_B=\nKEEP_C=\nNEW_ONE=\n") => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "env-lines-"));
  fs.writeFileSync(path.join(dir, ".env"), envText, { mode: 0o600 });
  fs.chmodSync(path.join(dir, ".env"), 0o600);
  fs.writeFileSync(path.join(dir, ".env.example"), exampleText);
  return dir;
};

test("checkEnv reads .env and .env.example in the directory and returns keys and the path only", () => {
  const dir = tmpProject(MIXED);
  const r = checkEnv({ dir });
  assert.deepEqual(r, { unused: ["OLD_ONE", "OLD_TWO"], missing: ["NEW_ONE"], file: path.join(dir, ".env") });
  assert.doesNotMatch(JSON.stringify(r), /SECRET_VALUE/);
});

test("checkEnv answers { error } for a missing .env, a missing .env.example and no directory, never throwing", () => {
  const dir = tmpProject("A=1\n");
  fs.unlinkSync(path.join(dir, ".env.example"));
  assert.match(checkEnv({ dir }).error, /could not read \.env\.example/);
  fs.unlinkSync(path.join(dir, ".env"));
  assert.match(checkEnv({ dir }).error, /could not read \.env /);
  assert.match(checkEnv({ error: "the compose project directory is unknown: x" }).error, /unknown/);
  assert.ok(checkEnv(null).error);
});

test("removeEnv rewrites the file in place: only the named lines go, the bytes, inode and mode stay", () => {
  const dir = tmpProject(MIXED);
  const file = path.join(dir, ".env");
  const before = fs.statSync(file);
  const last = checkEnv({ dir });
  const r = removeEnv({ keys: ["OLD_ONE", "OLD_TWO"], last });
  assert.equal(r.removed, 3);
  assert.deepEqual(r.check, { unused: [], missing: ["NEW_ONE"], file });
  assert.doesNotMatch(JSON.stringify(r), /SECRET_VALUE/);
  assert.equal(fs.readFileSync(file, "latin1"), removeLines(MIXED, ["OLD_ONE", "OLD_TWO"]).text);
  const after = fs.statSync(file);
  assert.equal(after.ino, before.ino);
  assert.equal(after.mode & 0o7777, 0o600);
  assert.deepEqual(fs.readdirSync(dir).sort(), [".env", ".env.example"], "no backup or temp file");
});

test("removeEnv keeps non-UTF-8 bytes of the kept lines", () => {
  const dir = tmpProject("");
  const file = path.join(dir, ".env");
  const bytes = Buffer.concat([Buffer.from("KEEP_A=\xff\xfe", "latin1"), Buffer.from("\nOLD_ONE=x\n")]);
  fs.writeFileSync(file, bytes);
  const r = removeEnv({ keys: ["OLD_ONE"], last: checkEnv({ dir }) });
  assert.equal(r.removed, 1);
  assert.deepEqual(fs.readFileSync(file), Buffer.from("KEEP_A=\xff\xfe\n", "latin1"));
});

test("removeEnv refuses a key .env.example declares, even if the last check called it unused", () => {
  const dir = tmpProject("KEEP_A=1\nOLD_ONE=SECRET_VALUE\n");
  const last = checkEnv({ dir });
  fs.writeFileSync(path.join(dir, ".env.example"), "KEEP_A=\nOLD_ONE=\n");
  const r = removeEnv({ keys: ["OLD_ONE"], last });
  assert.match(r.error, /declared in \.env\.example/);
  assert.equal(fs.readFileSync(path.join(dir, ".env"), "utf8"), "KEEP_A=1\nOLD_ONE=SECRET_VALUE\n");
  assert.match(removeEnv({ keys: ["KEEP_A"], last }).error, /declared|not an unused line/);
  assert.match(removeEnv({ keys: ["OLD_ONE"], last: null }).error, /check \.env first/);
});

test("projectDir: a checkout run uses the checkout; a packaged build the engine's compose working_dir", () => {
  assert.deepEqual(projectDir({ packaged: false, repoDir: "/src/frakworks" }), { dir: "/src/frakworks" });
  assert.deepEqual(projectDir({ packaged: true, state: { running: { project: { workingDir: "/srv/stack" } } } }), { dir: "/srv/stack" });
  assert.match(projectDir({ packaged: true, state: { running: null, runningError: "no running compose engine" } }).error,
    /compose project directory is unknown: no running compose engine/);
});

test("the confirm names the file and the exact keys, and the revoke note", () => {
  const c = confirmText("/srv/stack/.env", ["OLD_ONE", "OLD_TWO"]);
  assert.equal(c.message, "Remove 2 unused lines from .env?");
  assert.match(c.detail, /\/srv\/stack\/\.env/);
  assert.match(c.detail, /^ {2}OLD_ONE$/m);
  assert.match(c.detail, /^ {2}OLD_TWO$/m);
  assert.match(c.detail, /does not revoke/);
  assert.equal(confirmText("/x/.env", ["A"]).message, "Remove 1 unused line from .env?");
});

// ---------------------------------------------------------------------------
// The section
// ---------------------------------------------------------------------------

const draw = (props) => renderToStaticMarkup(React.createElement(EnvLines, props));

test("phrases: singular and plural", () => {
  assert.equal(unusedPhrase(1), "1 unused line in .env");
  assert.equal(unusedPhrase(11), "11 unused lines in .env");
  assert.equal(removedPhrase(1), "Removed 1 line");
  assert.equal(removedPhrase(3), "Removed 3 lines");
});

test("unused keys: the count, the keys, Remove and the revoke note", () => {
  const html = draw({ result: { unused: ["KEYCLOAK_SECRET", "OLD_TOKEN"], missing: [], file: "/srv/stack/.env" }, onRemove: () => {} });
  assert.match(html, /class="acSetting envLines"/);
  assert.match(html, /class="acName">\.env</);
  assert.match(html, /2 unused lines in \.env/);
  assert.match(html, /<li>KEYCLOAK_SECRET<\/li><li>OLD_TOKEN<\/li>/);
  assert.match(html, />Remove<\/button>/);
  assert.ok(html.includes(REVOKE_NOTE.replace(/'/g, "&#x27;")));
  assert.doesNotMatch(html, /Missing from \.env/);
});

test("missing keys are listed with no action", () => {
  const html = draw({ result: { unused: [], missing: ["ENGINE_SECRETS_KEY"], file: "/x/.env" }, onRemove: () => {} });
  assert.match(html, /Missing from \.env/);
  assert.match(html, /<li>ENGINE_SECRETS_KEY<\/li>/);
  assert.doesNotMatch(html, /<button/);
});

test("nothing unused or missing: only the name and the clean hint", () => {
  const html = draw({ result: { unused: [], missing: [], file: "/x/.env" }, onRemove: () => {} });
  assert.match(html, /class="acName">\.env</);
  assert.ok(html.includes(CLEAN_HINT));
  assert.doesNotMatch(html, /<li|<button|class="err"|\/x\/\.env/);
});

test("an error is said in the section's place", () => {
  const html = draw({ result: { error: "could not read .env (/x/.env): ENOENT" } });
  assert.match(html, /class="err">\.env check unavailable: could not read \.env \(\/x\/\.env\): ENOENT/);
  assert.doesNotMatch(html, /<li|<button/);
});

test("a removal's notice draws as a notice", () => {
  const html = draw({ result: { unused: [], missing: [] }, notice: { ok: true, text: "Removed 2 lines" } });
  assert.match(html, /class="acNotice">Removed 2 lines</);
});

test("the explorer is unchanged: no .env row", () => {
  const explorer = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "explorer.jsx"), "utf8");
  assert.doesNotMatch(explorer, /\.env|env-lines/);
});
