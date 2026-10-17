// transcript-format.js — what the desktop's live transcript pane shows of a
// Smith's stream-json run. It began as a port of the Operator's `smith-tail`
// formatter; the pane now keeps its own, narrower contract (#885). Three
// shapes render, everything else renders nothing:
//   * prose: each assistant `text` block, whitespace collapsed, ≤200 cp;
//   * a tool line: `→ <Name>  <description>` — the call's `description`
//     input, else its first non-empty string input, else the name alone;
//   * the run end: the `── result:` stats line and its `is_error` detail.
// Init events, thinking blocks and tool results (`user` events) are working
// output, not meant for the reader. Codex events keep their own lines.
// `truncate` counts code points; `jqNum`/`jqRound` keep the result line's
// numbers as they always printed.
"use strict";

const CODEX_HOOK_TRUST_WARNING =
  "`--dangerously-bypass-hook-trust` is enabled. Enabled hooks may run " +
  "without review for this invocation.";

const collapse = (text) => String(text).replace(/\s+/g, " ");
const trim = (text) => String(text).replace(/^\s+|\s+$/g, "");

const truncate = (text, limit) => {
  const cps = Array.from(String(text));
  return cps.length <= limit ? String(text) : cps.slice(0, limit).join("") + "…";
};

const jqNum = (v) => {
  if (v === null || v === undefined) return "null";
  if (typeof v === "boolean") return v ? "true" : "false";
  if (typeof v === "number") return String(v);
  return String(v);
};

const jqRound = (x) => (x >= 0 ? Math.floor(x + 0.5) : -Math.floor(-x + 0.5));

// jq's `tojson`: compact, in the object's own key order — which is what
// JSON.stringify already does for string keys that are not array indices.
const tojson = (value) => JSON.stringify(value === undefined ? null : value);

// jq `reset_hint`: the phrase after "resets"/"reset" in a result string.
const resetHint = (text) => {
  const m = /resets?\s+([^·\n]+)/i.exec(typeof text === "string" ? text : "");
  return m ? trim(m[1]) : "";
};

const CAUSE_LABEL = {
  spend: "spend limit reached",
  usage: "usage limit reached",
  session: "session limit reached",
  rate: "rate limited",
  api: "API error",
};

const LIMIT_NOTE_TEXT = {
  spend: "the Claude account hit its monthly spend limit",
  usage: "the Claude account hit a usage limit",
  session: "the Claude account hit its session limit",
  rate: "the Claude account was rate limited",
};

// jq `_cause_phrase`: which kind of account limit a result names, if any.
const causePhrase = (event) => {
  const text = typeof event.result === "string" ? event.result : "";
  if (/spend limit/i.test(text)) return "spend";
  if (/usage limit/i.test(text)) return "usage";
  if (/session limit/i.test(text)) return "session";
  const status = event.api_error_status || 0;
  if (/rate limit/i.test(text) || status === 429) return "rate";
  if (status) return `api:${status}`;
  if (event.terminal_reason === "api_error") return "api";
  return "";
};

// jq `endlabel`: the "(how it ended)" tail of the run-stats line. Not used by
// the pane's per-line rendering, but it is half of what `result` events mean
// and porting it keeps the mirror complete for the stats line below.
const endLabel = (event) => {
  const subtype = event.subtype || "success";
  if (!event.is_error) return `(${subtype})`;
  if (subtype.startsWith("error_")) return `(${subtype})`;
  const reset = resetHint(event.result);
  const cause = causePhrase(event);
  const label = cause.startsWith("api:")
    ? `API error ${cause.slice(4)}`
    : (CAUSE_LABEL[cause] || "error");
  return reset ? `(${label} · resets ${reset})` : `(${label})`;
};

// jq `limit_note`: a human sentence when the run died on an account limit.
// The pane prints it under the final result line so "the Smith stopped" and
// "the account stopped it" do not look the same.
const limitNote = (event) => {
  const subtype = event.subtype || "";
  if (!(event.is_error && !subtype.startsWith("error_"))) return "";
  const text = LIMIT_NOTE_TEXT[causePhrase(event)] || "";
  if (!text) return "";
  const reset = resetHint(event.result);
  return reset ? `${text} (resets ${reset})` : text;
};

// A tool call's one-line description: its `description` input, else the first
// non-empty string input in key order (Read's file_path, Grep's pattern).
const toolDescription = (input) => {
  const obj = input && typeof input === "object" ? input : {};
  const pick = (v) => (typeof v === "string" ? trim(collapse(v)) : "");
  let desc = pick(obj.description);
  if (!desc) {
    for (const v of Object.values(obj)) { desc = pick(v); if (desc) break; }
  }
  return desc ? truncate(desc, 100) : "";
};

const formatAssistant = (event) => {
  const out = [];
  const content = ((event.message || {}).content) || [];
  for (const item of content) {
    if (item.type === "text") {
      const x = trim(item.text || "");
      if (x === "") continue;
      out.push(`${truncate(collapse(x), 200)}`);
    } else if (item.type === "tool_use") {
      const desc = toolDescription(item.input);
      out.push(desc ? `→ ${item.name}  ${desc}` : `→ ${item.name}`);
    }
  }
  return out;
};

const formatResult = (event) => {
  const suffix = event.is_error ? " · is_error" : "";
  const durationS = Math.trunc((event.duration_ms || 0) / 1000);
  const cost = jqRound((event.total_cost_usd || 0) * 10000) / 10000;
  let line = `── result: ${jqNum(event.subtype)}${suffix} · ` +
    `${jqNum(event.num_turns === undefined ? null : event.num_turns)} turns · ` +
    `${durationS}s · ~$${jqNum(cost)}`;
  if (event.is_error && typeof event.result === "string" && event.result !== "") {
    line += `\n   ↳ ${truncate(collapse(event.result), 200)}`;
  }
  return line;
};

// observe.py `_format_event`: one event -> zero or more rendered entries, with
// no time column (#934). An entry may itself contain a newline (a result
// event's `is_error` detail); every rendering puts a blank line between entries.
function formatEvent(event) {
  const type = event.type;
  if (type === "assistant") return formatAssistant(event);
  if (type === "result") return [formatResult(event)];
  // Codex `exec --json` events.
  if (type === "thread.started") {
    return [`── codex thread ${String(event.thread_id || "").slice(0, 8)}`];
  }
  if (type === "item.completed") {
    const item = event.item || {};
    if (item.type === "agent_message") {
      const text = trim(item.text || "");
      return text ? [`${truncate(collapse(text), 200)}`] : [];
    }
    if (item.type === "error") {
      const detail = item.message || item.text || "Codex run failed";
      if (detail === CODEX_HOOK_TRUST_WARNING) return [];
      return [`── result: error\n   ↳ ${truncate(collapse(String(detail)), 200)}`];
    }
    if (["command_execution", "mcp_tool_call", "file_change"].includes(item.type)) {
      const detail = item.command || item.server || item.path || "";
      return [`→ ${item.type}  ${truncate(collapse(String(detail)), 100)}`];
    }
    return [];
  }
  if (type === "turn.completed") {
    const usage = event.usage || {};
    const total = ["input_tokens", "output_tokens"]
      .reduce((s, k) => s + Math.trunc(Number(usage[k] || 0)), 0);
    return [`── result: success · ${total} tokens`];
  }
  if (type === "turn.failed" || type === "error") {
    let detail = event.error || event.message || "Codex run failed";
    if (detail && typeof detail === "object") detail = detail.message || tojson(detail);
    return [`── result: error\n   ↳ ${truncate(collapse(String(detail)), 200)}`];
  }
  return [];
}

// observe.py `format_lines`: raw log lines -> rendered entries. Anything that
// is not a JSON object on its own line is dropped, exactly as the shell's
// `grep '^{'` does before jq sees it.
function formatLines(lines) {
  const out = [];
  for (const raw of lines) {
    const line = String(raw).replace(/\n+$/, "");
    if (!line.startsWith("{")) continue;
    let event;
    try { event = JSON.parse(line); } catch (e) { continue; }
    out.push(...formatEvent(event));
  }
  return out;
}

module.exports = {
  formatEvent, formatLines, endLabel, limitNote, resetHint, causePhrase,
  collapse, trim, truncate, jqNum, jqRound, tojson,
  CODEX_HOOK_TRUST_WARNING,
};
