// build.js — `npm run build:web`: the web version into dist-web/ (index.html,
// web.js, web.css, styles.css, web-overrides.css, manifest.webmanifest, icons/), the files the frakworks-web image
// serves at /web/ (web-image/Dockerfile). One esbuild bundle of src/web/index.jsx,
// which imports the desktop's renderer as it is. The shared main-process
// modules it reaches (src/engine-data.js and its neighbours) name node:
// built-ins (and `electron`); those resolve to src/web/node-shims/ here.
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const esbuild = require("esbuild");

const ROOT = path.join(__dirname, "..", "..");
const OUT = path.join(ROOT, "dist-web");
const SHIMS = path.join(__dirname, "node-shims");

const nodeShims = {
  name: "node-shims",
  setup(build) {
    // `electron` too: src/engine-events.js names it for main's window push, which the page replaces.
    build.onResolve({ filter: /^(node:|electron$)/ }, (args) => {
      const name = args.path.replace(/^node:/, "");
      const shim = path.join(SHIMS, `${name}.js`);
      return { path: fs.existsSync(shim) ? shim : path.join(SHIMS, "empty.js") };
    });
  },
};

async function main() {
  fs.rmSync(OUT, { recursive: true, force: true });
  fs.mkdirSync(OUT, { recursive: true });
  await esbuild.build({
    entryPoints: [path.join(__dirname, "index.jsx")],
    bundle: true,
    outfile: path.join(OUT, "web.js"),
    format: "iife",
    jsx: "automatic",
    target: ["chrome120", "firefox120", "safari17"],
    platform: "browser",
    minify: true,
    define: { "process.env.NODE_ENV": "\"production\"" },
    plugins: [nodeShims],
    logLevel: "warning",
  });
  fs.copyFileSync(path.join(__dirname, "index.html"), path.join(OUT, "index.html"));
  fs.copyFileSync(path.join(__dirname, "web-overrides.css"), path.join(OUT, "web-overrides.css"));
  // Add to Home Screen (README, "iPad"): the manifest and its icons.
  fs.copyFileSync(path.join(__dirname, "manifest.webmanifest"), path.join(OUT, "manifest.webmanifest"));
  fs.cpSync(path.join(__dirname, "icons"), path.join(OUT, "icons"), { recursive: true });
  fs.copyFileSync(path.join(ROOT, "src", "renderer", "styles.css"), path.join(OUT, "styles.css"));
  // A bundle that imports no CSS writes no web.css; the page still links one.
  if (!fs.existsSync(path.join(OUT, "web.css"))) fs.writeFileSync(path.join(OUT, "web.css"), "");
  console.log(`web version built into ${path.relative(process.cwd(), OUT) || "."}: ${fs.readdirSync(OUT).sort().join(", ")}`);
}

main().catch((e) => { console.error(e && e.message ? e.message : e); process.exit(1); });
