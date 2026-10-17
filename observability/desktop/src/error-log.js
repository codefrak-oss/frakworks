// error-log.js — the persisted error log (#985): `<userData>/errors.log`,
// JSON Lines, error level only, the last `cap` entries. src/main.js is the only
// place that wires it to the process, the windows and the menu.
//
// Appending is synchronous so an entry written just before a crash is kept.
// Rollover is by entry count: one file, the oldest lines dropped so the new
// entry is the `cap`th. A writer never throws: a log that cannot be written
// must not turn a caught error into an uncaught one.
"use strict";

const fs = require("node:fs");
const path = require("node:path");

const ERROR_LOG_CAP = 72;

const str = (v) => (v == null ? null : String(v));

// `{ source, message, stack?, context? }`, or an Error as `message`'s source.
function entryOf({ source, message, stack, context, error } = {}, now = new Date()) {
  const msg = message != null ? message : error && error.message != null ? error.message : error;
  return {
    at: now.toISOString(),
    source: String(source || "main"),
    message: msg == null ? "" : String(msg),
    stack: str(stack != null ? stack : error && error.stack),
    context: str(context),
  };
}

function createErrorLog({ file, cap = ERROR_LOG_CAP } = {}) {
  if (!file) throw new Error("error log: no file");
  const ensure = () => {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    if (!fs.existsSync(file)) fs.writeFileSync(file, "");
  };
  const append = (e) => {
    try {
      const line = JSON.stringify(entryOf(e));
      fs.mkdirSync(path.dirname(file), { recursive: true });
      let lines = [];
      try { lines = fs.readFileSync(file, "utf8").split("\n").filter((l) => l); } catch { /* absent */ }
      if (lines.length >= cap) {
        lines = lines.slice(lines.length - (cap - 1));
        fs.writeFileSync(file, lines.map((l) => `${l}\n`).join("") + `${line}\n`);
      } else {
        fs.appendFileSync(file, `${line}\n`);
      }
    } catch (err) {
      console.log(`[error-log] could not write ${file}: ${err && err.message}`);
    }
  };
  return { file, cap, append, ensure };
}

module.exports = { createErrorLog, entryOf, ERROR_LOG_CAP };
