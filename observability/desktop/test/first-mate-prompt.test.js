// The First mate prompt and allow/deny lists come from shared/first-mate-prompt.json
// (shared with the iPhone app, ios/README.md): the lists still equal what the code derives.
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const fs = require("node:fs");
const os = require("node:os");
const s = require("../src/claude-session");

const shared = require("../../../shared/first-mate-prompt.json");

test("the shared prompt joined is FIRST_MATE_SYSTEM_PROMPT", () => {
  assert.strictEqual(shared.prompt.join(""), s.FIRST_MATE_SYSTEM_PROMPT);
});

test("the shared lists equal the read, filing and deny tools", () => {
  assert.deepStrictEqual(shared.allowedTools, [...s.CHAT_READ_TOOLS, ...s.FIRST_MATE_FILING_TOOLS]);
  assert.deepStrictEqual([...s.FIRST_MATE_ALLOWED_TOOLS], shared.allowedTools);
  assert.deepStrictEqual(shared.deniedTools, s.CHAT_DENIED_TOOLS.filter((t) => t !== "Write" && t !== "Edit"));
  assert.deepStrictEqual([...s.FIRST_MATE_DENIED_TOOLS], shared.deniedTools);
});

test("packaged, the file is read from process.resourcesPath", (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "fm-res-"));
  fs.writeFileSync(path.join(dir, "first-mate-prompt.json"), "{}");
  const prev = process.resourcesPath;
  process.resourcesPath = dir;
  t.after(() => { process.resourcesPath = prev; });
  assert.strictEqual(s.firstMatePromptPath(), path.join(dir, "first-mate-prompt.json"));
});

test("in dev, the file is read from the repo's shared/", () => {
  const prev = process.resourcesPath;
  process.resourcesPath = undefined;
  try {
    assert.strictEqual(s.firstMatePromptPath(), path.resolve(__dirname, "../../../shared/first-mate-prompt.json"));
  } finally { process.resourcesPath = prev; }
});
