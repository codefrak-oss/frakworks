// repo-files.js — the explorer's Repos branch (#674), the parts that are not
// drawing: the engine's flat tree (`GET /repos/{owner}/{repo}/tree`, every
// path at one commit) built into folders, and a file's bytes
// (`GET /repos/{owner}/{repo}/file`) sorted into what the middle pane can show.
// Folders are built here, from the paths, so opening one asks nobody.
"use strict";

/** The middle pane shows at most this much of a text file; the engine sends it whole. */
const MAX_SHOWN_BYTES = 256 * 1024;
/** A NUL byte this far in is a binary file. */
const SNIFF_BYTES = 8 * 1024;
/** Extensions that are binary whatever their first bytes say. */
const BINARY_EXTENSIONS = new Set([
  "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "icns", "tif", "tiff", "psd", "heic",
  "pdf", "zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "tar", "jar", "war", "class", "dex",
  "so", "dylib", "dll", "exe", "o", "a", "bin", "dmg", "iso", "wasm", "pyc",
  "woff", "woff2", "ttf", "otf", "eot", "mp3", "mp4", "m4a", "mov", "avi", "wav", "ogg", "flac", "webm",
  "sqlite", "db", "keystore", "jks", "p12",
]);
const MARKDOWN_EXTENSIONS = new Set(["md", "markdown"]);

const extensionOf = (path) => {
  const name = String(path || "").split("/").pop();
  const dot = name.lastIndexOf(".");
  return dot > 0 ? name.slice(dot + 1).toLowerCase() : "";
};

const byName = (a, b) => (a.type === "tree") !== (b.type === "tree")
  ? (a.type === "tree" ? -1 : 1)
  : a.name.localeCompare(b.name, undefined, { sensitivity: "base" }) || a.name.localeCompare(b.name);

/**
 * The engine's `entries` (`{ path, type, sha }`, every path from the root) as
 * nested nodes `{ name, path, type, sha, children }`: folders first, then by
 * name. A folder a truncated tree never listed is made from its files' paths.
 * `type` stays GitHub's: `tree` a folder, `blob` a file, `commit` a submodule.
 */
function buildTree(entries) {
  const root = { name: "", path: "", type: "tree", children: [] };
  const dirs = new Map([["", root]]);
  const dir = (path) => {
    if (dirs.has(path)) return dirs.get(path);
    const cut = path.lastIndexOf("/");
    const parent = dir(cut < 0 ? "" : path.slice(0, cut));
    const node = { name: path.slice(cut + 1), path, type: "tree", sha: null, children: [] };
    parent.children.push(node);
    dirs.set(path, node);
    return node;
  };
  for (const e of Array.isArray(entries) ? entries : []) {
    if (!e || typeof e.path !== "string" || !e.path) continue;
    if (e.type === "tree") { dir(e.path).sha = e.sha || null; continue; }
    const cut = e.path.lastIndexOf("/");
    dir(cut < 0 ? "" : e.path.slice(0, cut)).children.push({ name: e.path.slice(cut + 1), path: e.path,
      type: e.type === "commit" ? "commit" : "blob", sha: e.sha || null });
  }
  const sort = (n) => { if (n.children) { n.children.sort(byName); n.children.forEach(sort); } };
  sort(root);
  return root.children;
}

/** How many files (not folders) the entries name. */
const fileCount = (entries) => (Array.isArray(entries) ? entries : []).filter((e) => e && e.type === "blob").length;

/** `n` bytes, short: `812 B`, `3.4 KiB`, `1.2 MiB`. */
const formatSize = (n) => {
  if (!Number.isFinite(n)) return "?";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(n < 10 * 1024 ? 1 : 0)} KiB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MiB`;
};

/**
 * What the middle pane shows for a file's `bytes` (a Uint8Array or Buffer):
 * `{ kind: "binary", size }` for a known binary extension or a NUL in the
 * first 8 KiB; else `{ kind: "markdown" | "text", text, size, truncated }`,
 * `text` at most the first 256 KiB decoded as UTF-8.
 */
function fileView(path, bytes) {
  const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes || []);
  const size = b.length;
  const ext = extensionOf(path);
  if (BINARY_EXTENSIONS.has(ext) || b.subarray(0, SNIFF_BYTES).includes(0)) return { kind: "binary", size };
  const truncated = size > MAX_SHOWN_BYTES;
  const text = new TextDecoder("utf-8").decode(truncated ? b.subarray(0, MAX_SHOWN_BYTES) : b);
  return { kind: MARKDOWN_EXTENSIONS.has(ext) ? "markdown" : "text", text, size, truncated };
}

/** A file's explorer id, `owner/name:path`, and back (null when it is not one). */
const repoFileId = (repo, path) => `${repo}:${path}`;
const parseRepoFileId = (id) => {
  const m = /^([\w.-]+)\/([\w.-]+):(.+)$/.exec(String(id || ""));
  return m ? { owner: m[1], name: m[2], repo: `${m[1]}/${m[2]}`, path: m[3] } : null;
};

module.exports = { buildTree, fileCount, fileView, formatSize, extensionOf, repoFileId, parseRepoFileId,
  MAX_SHOWN_BYTES, SNIFF_BYTES, BINARY_EXTENSIONS };
