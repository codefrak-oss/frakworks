// The desktop reads GitHub only through the engine (#229, #262). No module
// under src/ may spawn `gh` or name api.github.com (the login comes from the
// engine bearer token, not from `gh api user`).
//
// What counts is code, not prose: comments are stripped before the scan, and a
// string that merely lists `gh` commands (Claude's permission allowlists in
// src/claude-session.js) is not a call. What is caught:
//   * the string "gh" on its own, the command argument of `spawn`,
//     `execFile`, their sync forms, or any helper that wraps them;
//   * `exec("gh …")` / `execSync("gh …")` and `"-c", "gh …"`, gh through a shell;
//   * `api.github.com` anywhere in code.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const SRC = path.join(__dirname, "..", "src");
const EXTENSIONS = new Set([".js", ".ts", ".jsx", ".tsx"]);

const RULES = [
  // "gh" as a value (an argument, an element, an assignment), not `gh` quoted inside prose.
  { why: "spawns `gh`", re: /(?:^|[(,=[:?]|\breturn)\s*(["'`])gh\1/ },
  { why: "runs `gh` through a shell", re: /\bexec(?:Sync)?\s*\(\s*(["'`])gh\s/ },
  { why: "runs `gh` through a shell", re: /(["'`])-c\1\s*,\s*(["'`])gh\s/ },
  { why: "references api.github.com", re: /api\.github\.com/i },
];

/** Every module under `dir`, recursively: { relative path: source }. */
function modules(dir, base = dir) {
  const out = {};
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) Object.assign(out, modules(full, base));
    else if (EXTENSIONS.has(path.extname(entry.name))) out[path.relative(base, full).split(path.sep).join("/")] = fs.readFileSync(full, "utf8");
  }
  return out;
}

// After one of these (or at the start), a `/` opens a regex literal, not a division.
const REGEX_BEFORE = /(?:^|[(,=:[!&|?{};+\-*%<>~^]|\breturn|\btypeof|\bcase)\s*$/;

/**
 * `source` with its comments blanked out (newlines kept, so line numbers
 * hold) and everything else as it was. Strings and regex literals are skipped
 * over so a `//` inside one is not taken for a comment; a ' or " string ends
 * at the line's end at the latest, so an apostrophe in JSX text cannot swallow
 * the rest of the file.
 */
function stripComments(source) {
  let out = "", i = 0;
  const n = source.length;
  while (i < n) {
    const c = source[i], d = source[i + 1];
    if (c === "/" && d === "/") {
      while (i < n && source[i] !== "\n") { out += " "; i += 1; }
    } else if (c === "/" && d === "*") {
      const end = source.indexOf("*/", i + 2);
      const stop = end < 0 ? n : end + 2;
      out += source.slice(i, stop).replace(/[^\n]/g, " ");
      i = stop;
    } else if (c === '"' || c === "'" || c === "`") {
      let j = i + 1;
      while (j < n && source[j] !== c && (c === "`" || source[j] !== "\n")) j += source[j] === "\\" ? 2 : 1;
      out += source.slice(i, j + 1);
      i = j + 1;
    } else if (c === "/" && REGEX_BEFORE.test(out)) {
      let j = i + 1, klass = false;
      while (j < n && source[j] !== "\n" && (klass || source[j] !== "/")) {
        if (source[j] === "\\") j += 1;
        else if (source[j] === "[") klass = true;
        else if (source[j] === "]") klass = false;
        j += 1;
      }
      out += source.slice(i, j + 1);
      i = j + 1;
    } else { out += c; i += 1; }
  }
  return out;
}

/** Every GitHub call outside the engine in `files` ({ path: source }), as "path:line: why". */
function findGitHubCalls(files) {
  const found = [];
  for (const [file, source] of Object.entries(files)) {
    stripComments(source).split("\n").forEach((line, k) => {
      for (const rule of RULES) {
        if (!rule.re.test(line)) continue;
        found.push(`${file}:${k + 1}: ${rule.why}`);
      }
    });
  }
  return found;
}

test("no module under src/ reads GitHub except through the engine", () => {
  const files = modules(SRC);
  assert.ok(Object.keys(files).length > 20, "the scan found the modules");
  assert.ok(Object.keys(files).some((f) => f.startsWith("renderer/") && f.endsWith(".jsx")), "the scan reaches src/renderer");
  assert.deepEqual(findGitHubCalls(files), []);
});

test("main.js spawns no gh at all", () => {
  const main = stripComments(modules(SRC)["main.js"] || "");
  assert.ok(main.length > 0, "the scan found main.js");
  assert.doesNotMatch(main, /["'`]gh["'`]/);
  assert.deepEqual(findGitHubCalls({ "main.js": main }), []);
});

test("src/gh.js is gone", () => {
  assert.equal(fs.existsSync(path.join(SRC, "gh.js")), false);
});

// ---- the scanner itself, on in-memory samples -------------------------------

const LOGIN = 'const who = () => require("node:child_process").execFile("gh", ["api", "user", "--jq", ".login"], cb);\n';

test("adding spawn(\"gh\", …) to any other module fails the scan", () => {
  const files = { "main.js": "const x = 1;\n", "x.js": 'const { spawn } = require("node:child_process");\nspawn("gh", ["issue", "view", "1"]);\n' };
  assert.deepEqual(findGitHubCalls(files), ["x.js:2: spawns `gh`"]);
});

test("every spawning form is caught, in every extension and folder", () => {
  const cases = {
    "a.js": "child_process.spawnSync('gh', ['api', 'user']);",
    "b.ts": "execFile(`gh`, args, cb);",
    "c.js": 'cp.execFileSync("gh", ["pr", "list"]);',
    "d.js": 'exec("gh api repos/o/r/issues", cb);',
    "e.js": "execSync('gh issue list');",
    "renderer/f.jsx": 'const run = (a) => runCommand("gh", a);',
    "renderer/g.tsx": 'spawn("sh", ["-c", "gh api user"]);',
    "h.js": 'const GH = "gh"; spawn(GH, ["api"]);',
    "i.js": 'fetch("https://api.github.com/repos/o/r/issues");',
    "j.js": "const base = `https://API.github.com/${repo}`;",
  };
  const found = findGitHubCalls(cases);
  for (const file of Object.keys(cases)) assert.ok(found.some((f) => f.startsWith(`${file}:1:`)), `${file} is caught: ${found}`);
});

test("a gh call in main.js is caught, the login one too", () => {
  assert.deepEqual(findGitHubCalls({ "main.js": 'spawn("gh", ["issue", "list"]);\n' }), ["main.js:1: spawns `gh`"]);
  assert.deepEqual(findGitHubCalls({ "main.js": LOGIN }), ["main.js:1: spawns `gh`"]);
});

test("comments and allowlists of gh commands are not calls", () => {
  const files = {
    "a.js": [
      '// spawn("gh", ["api", "user"]) was how this used to read it, from https://api.github.com',
      '/* execFile("gh", …) */ const x = 1;',
      "const ALLOW = [\"Bash(gh issue view:*)\", \"Bash(gh api:*)\"];",
      'const DENY = ["gh pr create", "gh issue comment", "gh api"];',
      'const hint = "sign `gh` in";',
      "const re = /\\/\\/ not a comment/; const y = 2;",
    ].join("\n"),
    "b.jsx": "const P = () => <p>it's fine</p>;\n{/* spawn(\"gh\") */}",
  };
  assert.deepEqual(findGitHubCalls(files), []);
});

test("stripComments keeps lines and strings, and blanks only comments", () => {
  const src = 'a("x // y"); // gone\n/* gone\ngone */ b(\'z\');\nconst r = /\\/*x/; c();';
  const out = stripComments(src);
  assert.equal(out.split("\n").length, src.split("\n").length);
  assert.match(out, /a\("x \/\/ y"\);\s+\n/);
  assert.doesNotMatch(out, /gone/);
  assert.match(out, /b\('z'\);/);
  assert.match(out, /const r = \/\\\/\*x\/; c\(\);/);
});
