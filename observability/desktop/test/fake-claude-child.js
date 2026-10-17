// fake-claude-child.js — the stand-in `claude -p --output-format stream-json`
// child every chat-pane test runs against (#1326).
//
// Same idea as the tiny shell scripts test/fleet-data.test.js writes into a
// temp dir, one step up: this one speaks stream-json over a pipe, so the
// adapter's real `spawn`, real line reader and real JSON parsing are exercised.
// **No test in this suite invokes the real `claude` binary or needs a Claude
// login** — that is the point of this file, and the reason `npm test` passes on
// a machine that has never signed in.
//
// `makeFakeChild(dir, config)` writes the script and returns what a manager
// needs to launch it: `{ command, launchArgs }`.
"use strict";

const fs = require("node:fs");
const path = require("node:path");

// The script itself. Written out as a string so it is a genuinely separate
// process — a fake wired in through a mocked `spawn` would not prove the
// adapter can read a pipe.
const SCRIPT = `
"use strict";
const fs = require("node:fs");
const path = require("node:path");
const readline = require("node:readline");
const cfg = JSON.parse(process.argv[2]);
const argv = process.argv.slice(3);            // what the adapter built
if (cfg.argvDump) fs.writeFileSync(cfg.argvDump, JSON.stringify(argv));
// The directory the adapter spawned us in (First mate's fresh clones, #960).
if (cfg.cwdDump) fs.writeFileSync(cfg.cwdDump, process.cwd());
// The lineage file the adapter named, and what it held as the child started (engine/README.md, "Lineage").
if (cfg.lineageDump) fs.writeFileSync(cfg.lineageDump, JSON.stringify({ file: process.env.FM_LINEAGE_FILE || null,
  text: process.env.FM_LINEAGE_FILE ? fs.readFileSync(process.env.FM_LINEAGE_FILE, "utf8") : null }));
// Every start's argv, one JSON line each, so a test can see a restart sequence.
if (cfg.argvLog) fs.appendFileSync(cfg.argvLog, JSON.stringify(argv) + "\\n");
// First mate's Fable-limit fallback (#231): on --model fable, every turn's
// result is the text the CLI gives once the Fable allowance is used up.
const modelAt = argv.indexOf("--model");
const fableLimited = cfg.fableLimited && modelAt >= 0 && argv[modelAt + 1] === "fable";
const at = argv.indexOf("--resume");
const resumed = at >= 0 ? argv[at + 1] : null;
// The stable peer address the adapter gave us with --name (stage 2, #1334).
const nameAt = argv.indexOf("--name");
const myPeer = nameAt >= 0 ? argv[nameAt + 1] : null;
// A stale resume id: \`claude\` exits at once with "No conversation found".
if (cfg.dieOnResume && resumed) process.exit(7);
if (cfg.dieAtOnce) process.exit(7);
const sid = cfg.sessionId || resumed || "fake-session";
const say = (o) => process.stdout.write(JSON.stringify(o) + "\\n");
// The capability list a real \`claude -p --output-format stream-json\` sends
// (2.1.263): \`msg_lifecycle_v1\` is the session promising a
// \`user_message_uuid\` on the events it produces for a turn we sent.
// \`cfg.noMsgLifecycle\` drops it, which is how the adapter's arrival-order
// fallback for an older CLI is exercised.
const attributes = !cfg.noMsgLifecycle;
say({ type: "system", subtype: "init", session_id: sid,
      capabilities: attributes ? ["msg_lifecycle_v1"] : [] });
// The conversation itself, such as it is: a list of words it was told to
// remember, keyed by session id on disk. That is what makes \`--resume\` mean
// something to this fake — a resumed child picks the list up, a fresh one
// starts empty — which is what the restart test actually needs to prove.
const memFile = cfg.memDir ? cfg.memDir + "/mem-" + sid + ".json" : null;
let remembered = [];
if (memFile && resumed) { try { remembered = JSON.parse(fs.readFileSync(memFile, "utf8")); } catch (e) {} }
// ---------------------------------------------------------------------------
// The peer mailbox (stage 2, #1334)
// ---------------------------------------------------------------------------
//
// A file-per-peer stand-in for the CLI's own \`SendMessage\`: writing a line
// into \`<peerDir>/<peer name>.jsonl\` is "A sent B a message", and each child
// tails its own file. What the adapter under test sees is what the real thing
// gives it — an unsolicited \`result\` on stdout that no message of ours
// caused — so the adapter is exercised, not mocked.
//
// \`tell <peer name> <text>\` in a user message is the send. The address is
// the \`--name\` the adapter passed, never a display name: a fake that
// accepted a display name could not prove that renaming changes no routing.
const inboxFor = (who) => path.join(cfg.peerDir, who.replace(/[^A-Za-z0-9._-]/g, "_") + ".jsonl");
let offset = 0, seq = 0;
// Real sessions answer one turn at a time, so a peer message that arrives
// while a turn is in flight is answered *after* it. \`outstanding\` reproduces
// that ordering, which is the whole interleaving the routing has to survive.
let outstanding = 0;
function drainInbox() {
  if (!cfg.peerDir || !myPeer) return;
  // \`peerWhileBusy\` lets a peer's turn finish *before* ours, which is the
  // interleaving the adapter has to survive on its own rather than by the
  // fake's good manners (#1351 review).
  if (outstanding > 0 && !cfg.peerWhileBusy) return;   // busy: the peer's turn waits
  let raw = "";
  try { raw = fs.readFileSync(inboxFor(myPeer), "utf8"); } catch (e) { return; }
  if (raw.length <= offset) return;
  const fresh = raw.slice(offset);
  offset = raw.length;
  for (const line of fresh.split("\\n")) {
    if (!line.trim()) continue;
    let m;
    try { m = JSON.parse(line); } catch (e) { continue; }
    // An unsolicited result: exactly the shape \`mobile-bridge/server.py\`'s
    // \`_read\` calls "a turn another session started". No \`user\` replay —
    // --replay-user-messages re-emits messages from *stdin* only.
    // A turn *this* session ran for someone else: it names a user message
    // the adapter never wrote to stdin.
    const emit = () => say(Object.assign(
      { type: "result", subtype: "success", session_id: sid,
        uuid: "evt-" + m.id, result: m.text },
      attributes ? { user_message_uuid: "peer-msg-" + m.id } : {}));
    emit();
    // The same event twice, same id: what a reconnecting reader looks like.
    if (cfg.duplicatePeer) emit();
  }
}
if (cfg.peerDir) setInterval(drainInbox, 10);

readline.createInterface({ input: process.stdin }).on("line", (line) => {
  let msg;
  try { msg = JSON.parse(line); } catch (e) { return; }
  if (!msg || msg.type !== "user") return;
  // Every user message as written, one line each (First mate's images, #368).
  if (cfg.inputLog) fs.appendFileSync(cfg.inputLog, line + "\\n");
  const content = (msg.message && msg.message.content) || "";
  // Block content (image + text blocks, #368): the text blocks are the text.
  const text = Array.isArray(content)
    ? content.filter((b) => b && b.type === "text").map((b) => b.text).join("")
    : content;
  // The replay the adapter matches a turn against, uuid included.
  say({ type: "user", uuid: msg.uuid, session_id: sid, message: { role: "user", content } });
  // A turn that never finishes. Triggerable per message (the word HANG) as
  // well as per child.
  if (cfg.hang || /\\bHANG\\b/.test(text)) { outstanding += 1; return; }
  const word = /remember (\\w+)/.exec(text);
  if (word) {
    remembered.push(word[1]);
    if (memFile) { try { fs.writeFileSync(memFile, JSON.stringify(remembered)); } catch (e) {} }
  }
  // \`tell <peer> <text>\` — the SendMessage stand-in.
  const told = /^tell (\\S+) ([\\s\\S]+)$/.exec(text.trim());
  if (told && cfg.peerDir) {
    seq += 1;
    fs.appendFileSync(inboxFor(told[1]),
      JSON.stringify({ id: myPeer + "-" + seq, text: told[2] }) + "\\n");
  }
  // \`say <literal>\` — the reply verbatim, so a test can hand the adapter an
  // exact \`Options:\` marker without teaching this fake the contract.
  const literal = /^say ([\\s\\S]+)$/.exec(text.trim());
  const reply = fableLimited ? "You've reached your Fable limit. Switch to another model to continue."
    : literal ? literal[1].replace(/\\\\n/g, "\\n")
    : /what.*remember/i.test(text)
      ? (remembered.length ? remembered.join(",") : "nothing")
      : (cfg.replyPrefix || "echo") + ": " + text;
  outstanding += 1;
  const emit = () => {
    // First mate's own filing (#290): \`cfg.fileTool\` = { result, isError }
    // makes the turn call mcp__frakworks__submit_ask first — an assistant
    // tool_use, then the tool_result the CLI feeds back as a user event —
    // the way a real child does before its final result. \`cfg.fileTool.name\`
    // and \`.input\` call another of First mate's tools instead (#968:
    // mcp__frakworks__note_friction).
    if (cfg.fileTool) {
      const toolId = "toolu-" + msg.uuid;
      say(Object.assign(
        { type: "assistant", session_id: sid, uuid: "evt-t-" + msg.uuid,
          message: { role: "assistant", content: [{ type: "tool_use", id: toolId,
            name: cfg.fileTool.name || "mcp__frakworks__submit_ask", input: cfg.fileTool.input || { ask: text } }] } },
        attributes ? { user_message_uuid: msg.uuid } : {}));
      say({ type: "user", session_id: sid, uuid: "evt-tr-" + msg.uuid,
            message: { role: "user", content: [{ type: "tool_result", tool_use_id: toolId,
              is_error: Boolean(cfg.fileTool.isError),
              content: [{ type: "text", text: JSON.stringify(cfg.fileTool.result) }] }] } });
    }
    if (cfg.streamFirst) {
      say(Object.assign(
        { type: "assistant", session_id: sid, uuid: "evt-a-" + msg.uuid,
          message: { role: "assistant", content: [{ type: "text", text: reply }] } },
        attributes ? { user_message_uuid: msg.uuid } : {}));
    }
    say(Object.assign(
      { type: "result", subtype: "success", session_id: sid, uuid: "evt-r-" + msg.uuid,
        result: reply },
      attributes ? { user_message_uuid: msg.uuid } : {}));
    outstanding -= 1;
    drainInbox();
  };
  if (cfg.replyDelayMs) setTimeout(emit, cfg.replyDelayMs); else emit();
});
`;

let n = 0;

function makeFakeChild(dir, config = {}) {
  fs.mkdirSync(dir, { recursive: true });
  if (config.peerDir) fs.mkdirSync(config.peerDir, { recursive: true });
  const script = path.join(dir, `fake-claude-${n++}.js`);
  fs.writeFileSync(script, SCRIPT);
  return { command: process.execPath, launchArgs: [script, JSON.stringify(config)] };
}

module.exports = { makeFakeChild, SCRIPT };
