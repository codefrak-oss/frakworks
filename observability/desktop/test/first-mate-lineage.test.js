// The desktop First mate's HITLP lineage (engine/README.md, "Lineage"):
// FirstMateManager asks `lineageFn(ask)` (the engine's POST /first-mate/lineage)
// once per conversation, rooted at its first ask, and hands the text to the
// spawned child as data: a read-only file, byte for byte what the engine
// answered, never parsed or rewritten, whose literal path the appended system
// prompt names with the Read instruction (#2496: a shell read of
// `FM_LINEAGE_FILE` is refused by the permission check).
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { FirstMateManager } = require("../src/first-mate");
const { makeFakeChild } = require("./fake-claude-child");

// Odd spacing and key order on purpose: a re-serialized copy would not match.
const LINEAGE = '{"asOf":"2026-10-11T10:00:00Z",  "links":[{"kind":"session","claim":"Restart DNS.","source":{"login":"alice"},"by":"alice"}],"truncated":false}';

const start = async (lineageFn) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "first-mate-lineage-"));
  const dump = path.join(dir, "lineage-dump.json");
  const argvDump = path.join(dir, "argv-dump.json");
  const { command, launchArgs } = makeFakeChild(path.join(dir, "bin"), { lineageDump: dump, argvDump });
  const asked = [];
  const m = new FirstMateManager({
    dir, command, launchArgs, cwd: dir,
    submitIntakeFn: async () => ({ ok: true, intakeId: "intake-1" }),
    resolveTierFn: () => ({ model: "sonnet", effort: "medium" }),
    fetchFlowFn: async () => ({ error: "no engine in tests" }),
    engineEnvFn: async () => ({ ENGINE_URL: "http://127.0.0.1:9" }),
    lineageFn: lineageFn && (async (ask) => { asked.push(ask); return lineageFn(ask); }),
  }).init();
  const prompt = () => {
    const argv = JSON.parse(fs.readFileSync(argvDump, "utf8"));
    return argv[argv.indexOf("--append-system-prompt") + 1];
  };
  return { m, dump, asked, prompt };
};

test("the engine's lineage reaches the child unchanged, in a read-only file whose literal path the prompt gives for the Read tool", async () => {
  const { m, dump, asked, prompt } = await start(async () => ({ ok: true, lineage: LINEAGE }));
  await m.send("Restart DNS. It is stuck.");
  await m.send("And check it.");
  m.stop();
  assert.deepEqual(asked, ["Restart DNS. It is stuck."], "asked once, with the conversation's first ask");
  const seen = JSON.parse(fs.readFileSync(dump, "utf8"));
  assert.ok(seen.file, "FM_LINEAGE_FILE is set");
  assert.equal(seen.text, LINEAGE, "byte for byte what the engine answered");
  assert.equal(fs.statSync(seen.file).mode & 0o222, 0, "the file is read-only");
  const p = prompt();
  assert.ok(p.includes(`Lineage file: ${seen.file} (open it with the Read tool, never Bash).`), "the prompt names the literal path");
  assert.equal(fs.readFileSync(p.match(/Lineage file: (\S+) /)[1], "utf8"), LINEAGE, "the named path holds the lineage byte for byte");
  assert.ok(!p.includes("$FM_LINEAGE_FILE"), "nothing to shell out for");
});

test("an engine that gives no lineage leaves the child without the variable or a Lineage file line", async () => {
  const { m, dump, prompt } = await start(async () => ({ error: "engine down" }));
  await m.send("hello");
  m.stop();
  assert.deepEqual(JSON.parse(fs.readFileSync(dump, "utf8")), { file: null, text: null });
  assert.ok(!prompt().includes("Lineage file: "));
});
