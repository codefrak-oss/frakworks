// repo-files.jsx — the explorer's Repos branch and the file view (#674): each
// target repo's files at its default branch, read from GitHub through the
// engine (GET /repos/{owner}/{repo}/tree and /file), never from disk.
//
// A repo node asks for its whole tree once, when it is first opened
// (`fleet.repoTree`); its folders are built from the paths here
// (src/repo-files.js buildTree), so opening and closing them asks nobody. The
// trees are kept until the branch's ⟳ drops them and re-reads the repos that
// are open; nothing re-reads on a timer. A file is read when it is clicked
// (`fleet.repoFile`, at the tree's commit), by the view in the middle pane.
//
// Markdown is rendered here, with `marked`, not by GitHub: the file is already
// in hand. The HTML goes through DOMPurify with every tag that could fetch
// something removed (and the page's CSP allows no remote image anyway), so a
// doc draws with no request leaving the app.
//
// A text file is highlighted by its extension (#675, ./highlight.js); a
// markdown file is two panels, rendered above and its highlighted source
// below, with a draggable bar between (MarkdownSplit). Both halves come from
// the one file the engine sent. Read-only: no editing, no scroll sync, no line
// numbers.
//
// The branch draws its rows as plain elements, not child components, so a
// test can call it with its hooks stood in and press its rows.

import { useState, useEffect, useRef } from "react";
import DOMPurify from "dompurify";
import { Marked } from "marked";
import { highlightFile, highlightHtml } from "./highlight.js";
import { HSplitter, clampHeight, dragHeight, readHeight, saveHeight, MARKDOWN_SPLIT_KEY } from "./splitter.jsx";
import { buildTree, fileCount, fileView, formatSize, repoFileId, parseRepoFileId, MAX_SHOWN_BYTES } from "../repo-files.js";

const pad = (depth) => 8 + depth * 14;
const same = (a, b) => a && b && a.kind === b.kind && String(a.id ?? "") === String(b.id ?? "");
const errorOf = (e) => `IPC failed: ${e && e.message ? e.message : e}`;

/**
 * The Repos branch. `repos` is the repos it shows, one node each: the repo
 * switcher's selection, or every target repo under All repos. `fleet` is
 * window.fleet (a prop for tests). Closed until opened, unless the page
 * restored is one of its files.
 */
export const ReposBranch = ({ repos, sel, onSelect, fleet = typeof window !== "undefined" ? window.fleet : null }) => {
  const [open, setOpen] = useState(() => ({ repos: !!(sel && sel.kind === "repo-file") }));
  // repo -> { loading } | { commit, truncated, files, nodes } | { error }
  const [trees, setTrees] = useState({});
  const toggle = (k) => setOpen((o) => ({ ...o, [k]: !o[k] }));
  const list = (repos || []).filter((r) => typeof r === "string" && r.includes("/"));

  const load = async (repo) => {
    setTrees((t) => ({ ...t, [repo]: { loading: true } }));
    const [owner, name] = repo.split("/");
    let r;
    if (!(fleet && fleet.repoTree)) r = { error: "no engine bridge" };
    else { try { r = await fleet.repoTree(owner, name); } catch (e) { r = { error: errorOf(e) }; } }
    const next = !r ? { error: "no response from the main process" } : r.error ? { error: r.error }
      : { commit: r.tree.commit, truncated: !!r.tree.truncated, files: fileCount(r.tree.entries), nodes: buildTree(r.tree.entries) };
    setTrees((t) => ({ ...t, [repo]: next }));
  };
  const toggleRepo = (repo) => {
    const k = `r:${repo}`;
    if (!open[k] && !trees[repo]) load(repo);
    toggle(k);
  };
  // ⟳: drop every kept tree, then re-read the repos that are open.
  const refresh = (e) => {
    if (e && e.stopPropagation) e.stopPropagation();
    setTrees({});
    list.filter((repo) => open[`r:${repo}`]).forEach(load);
  };

  const fileRow = (repo, commit, n, depth) => {
    if (n.type === "commit") {
      return (
        <div key={`f:${repo}:${n.path}`} className="exRow exFile exSubmodule" style={{ paddingLeft: pad(depth) }} title={`${n.path} (a submodule)`}>
          <span className="exChev" />
          <span className="exLabel">{n.name}</span>
          <span className="exSuffix">submodule</span>
        </div>
      );
    }
    const item = { kind: "repo-file", id: repoFileId(repo, n.path), ref: commit };
    return (
      <div key={`f:${repo}:${n.path}`} className={"exRow exFile" + (same(sel, item) ? " on" : "")} style={{ paddingLeft: pad(depth) }}
           title={n.path} onClick={() => onSelect(item)}>
        <span className="exChev" />
        <span className="exLabel">{n.name}</span>
      </div>
    );
  };
  const nodeRows = (repo, commit, nodes, depth) => nodes.flatMap((n) => {
    if (n.type !== "tree") return [fileRow(repo, commit, n, depth)];
    const k = `f:${repo}:${n.path}`;
    return [
      <div key={`f:${repo}:${n.path}`} className="exRow exFolder" style={{ paddingLeft: pad(depth) }} title={n.path} onClick={() => toggle(k)}>
        <span className="exChev">{open[k] ? "▾" : "▸"}</span>
        <span className="exLabel">{n.name}</span>
      </div>,
      ...(open[k] ? nodeRows(repo, commit, n.children, depth + 1) : []),
    ];
  });
  const repoRows = (repo) => {
    const k = `r:${repo}`;
    const t = trees[repo];
    return [
      <div key={k} className="exRow exBranch exRepo" style={{ paddingLeft: pad(1) }} title={repo} onClick={() => toggleRepo(repo)}>
        <span className="exChev">{open[k] ? "▾" : "▸"}</span>
        <span className="exLabel">{repo}</span>
        {t && t.files != null && <span className="exCount">{t.files}</span>}
      </div>,
      ...(!open[k] ? [] : !t || t.loading
        ? [<div key={`${k}:loading`} className="exEmpty" style={{ paddingLeft: pad(2) }}>loading…</div>]
        : t.error
        ? [<div key={`${k}:error`} className="exErr" style={{ paddingLeft: pad(2) }}>{t.error}</div>]
        : [
          ...(t.truncated ? [<div key={`${k}:truncated`} className="exErr exTruncated" style={{ paddingLeft: pad(2) }}
            title="GitHub's recursive tree stops at 100,000 entries or 7 MB; the files past that are not listed">tree truncated: GitHub sent part of it</div>] : []),
          ...nodeRows(repo, t.commit, t.nodes, 2),
          ...(t.nodes.length ? [] : [<div key={`${k}:empty`} className="exEmpty" style={{ paddingLeft: pad(2) }}>empty</div>]),
        ]),
    ];
  };

  return (
    <>
      <div className="exRow exBranch" style={{ paddingLeft: pad(0) }} onClick={() => toggle("repos")}>
        <span className="exChev">{open.repos ? "▾" : "▸"}</span>
        <span className="exLabel">Repos</span>
        <span className="exCount">{list.length}</span>
        <button className="exRefresh reposRefresh" title="Refresh: re-read the open repos' trees" onClick={refresh}>⟳</button>
      </div>
      {open.repos && (list.length
        ? list.flatMap(repoRows)
        : <div className="exEmpty" style={{ paddingLeft: pad(1) }}>{repos ? "none" : "loading…"}</div>)}
    </>
  );
};

/** Every tag that could fetch something, or run, or post: none of them is drawn. */
const FORBID_TAGS = ["img", "picture", "source", "video", "audio", "track", "iframe", "frame", "object", "embed",
  "link", "style", "script", "form", "input", "button", "base", "meta", "svg", "math"];
const FORBID_ATTR = ["style", "srcset", "background", "poster", "action", "formaction", "ping"];

const escapeHtml = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

/** `marked` of our own, with an image drawn as its alt text: a doc's picture would be a request. */
const md = new Marked({ gfm: true, async: false, renderer: { image: ({ text }) => `<span class="mdImage">[${escapeHtml(text)}]</span>` } });

/** DOMPurify as the page runs it; null where it cannot run (no DOM, as in the node tests). */
const purify = (html) => (DOMPurify.isSupported ? DOMPurify.sanitize(html, { FORBID_TAGS, FORBID_ATTR }) : null);

/** A markdown file as sanitized HTML, or null (the view then shows it as text). `sanitize` is injectable for tests. */
export const markdownHtml = (text, sanitize = purify) => sanitize(md.parse(String(text || "")));

/** Loads one file for the view: `{ file }` or `{ error }`, one `fleet.repoFile` call. */
export const loadRepoFile = async ({ id, ref, fleet }) => {
  const p = parseRepoFileId(id);
  if (!p) return { error: `not a repo file: ${JSON.stringify(id)}` };
  if (!(fleet && fleet.repoFile)) return { error: "no engine bridge" };
  try { return (await fleet.repoFile(p.owner, p.name, p.path, ref || null)) || { error: "no response from the main process" }; }
  catch (e) { return { error: errorOf(e) }; }
};

/** Source text as highlighted, read-only monospace: the escaped HTML highlight.js made. */
const Code = ({ language, html }) => (
  <pre className={`repoText repoCode hljs language-${language}`}><code dangerouslySetInnerHTML={{ __html: html }} /></pre>
);

/**
 * A markdown file: `html` (sanitized) rendered on top, `raw` (highlighted
 * markdown) below, a horizontal bar between. The top panel's height persists
 * per viewer under MARKDOWN_SPLIT_KEY; none stored is an even split.
 */
export const MarkdownSplit = ({ html, raw }) => {
  const box = useRef(null);
  const [total, setTotal] = useState(0);
  const [height, setHeight] = useState(() => readHeight(MARKDOWN_SPLIT_KEY));
  const latest = useRef(height);
  latest.current = height;
  useEffect(() => {
    const el = box.current;
    if (!el) return undefined;
    const measure = () => setTotal(el.clientHeight);
    measure();
    if (typeof ResizeObserver === "undefined") return undefined;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  // Unmeasured (the first paint, or no DOM), both panels share the height evenly by CSS.
  const top = total > 0 ? clampHeight(height, total) : null;
  return (
    <div className="repoSplit" ref={box}>
      <div className="repoPane repoRendered" style={top != null ? { flex: `0 0 ${top}px` } : undefined}>
        <div className="repoDoc md" dangerouslySetInnerHTML={{ __html: html }} />
      </div>
      <HSplitter height={top}
                 onDrag={(start, dy) => setHeight(clampHeight(dragHeight(start, dy), total))}
                 onEnd={() => saveHeight(MARKDOWN_SPLIT_KEY, latest.current)}
                 onReset={() => { setHeight(null); saveHeight(MARKDOWN_SPLIT_KEY, null); }} />
      <div className="repoPane repoRaw"><Code language="markdown" html={raw} /></div>
    </div>
  );
};

/** The file itself: `result` is loadRepoFile's, or `{ loading: true }`. */
export const RepoFileBody = ({ path, result, render = markdownHtml }) => {
  if (!result || result.loading) return <div className="muted">asking the engine for the file…</div>;
  if (result.error) return <div className="err">{result.error}</div>;
  const v = fileView(path, result.file.bytes);
  if (v.kind === "binary") return <div className="muted repoBinary">binary file, not shown ({formatSize(v.size)}, {v.size} bytes)</div>;
  const cut = v.truncated
    ? <div className="muted repoTruncated">truncated at {formatSize(MAX_SHOWN_BYTES)} of {formatSize(v.size)} ({v.size} bytes)</div> : null;
  // Highlighting runs on the text fileView already cut to 256 KiB, never the whole file.
  const html = v.kind === "markdown" ? render(v.text) : null;
  const body = v.kind === "markdown"
    // No sanitizer (no DOM): the source alone, never unsanitized HTML.
    ? (html != null ? <MarkdownSplit html={html} raw={highlightHtml(v.text, "markdown")} /> : <Code language="markdown" html={highlightHtml(v.text, "markdown")} />)
    : <Code {...highlightFile(path, v.text)} />;
  return (
    <>
      {body}
      {cut}
    </>
  );
};

/** A file the view drew as text or markdown (not loading, not an error, not binary): the ones Chat about this offers. */
export const isChattable = (path, result) =>
  !!(result && !result.loading && !result.error && result.file && fileView(path, result.file.bytes).kind !== "binary");

/**
 * The middle pane for a Repos branch file: `id` is `owner/name:path`, `commit`
 * the tree's commit it was picked from. Chat about this (#683) sits over the
 * top-right corner, after the path line and the body so neither moves with it
 * (the path line keeps room for it either way), and hands `{ repo, path,
 * commit }` to `onChatAbout` (index.jsx chatAbout, as a Pitch view does).
 */
export const RepoFileView = ({ id, commit, onChatAbout, fleet = typeof window !== "undefined" ? window.fleet : null }) => {
  const [result, setResult] = useState({ loading: true });
  useEffect(() => {
    let live = true;
    setResult({ loading: true });
    loadRepoFile({ id, ref: commit, fleet }).then((r) => { if (live) setResult(r); });
    return () => { live = false; };
  }, [id, commit]);
  const p = parseRepoFileId(id);
  return (
    <div className="fleet-root repoFileView">
      <h2>{p ? p.path : String(id)} <span className="muted">· {p ? p.repo : ""}{commit ? ` @ ${String(commit).slice(0, 7)}` : ""}</span></h2>
      <RepoFileBody path={p ? p.path : ""} result={result} />
      {p && onChatAbout && isChattable(p.path, result)
        ? <button type="button" className="repoChat" title="Start a new First Mate conversation about this file"
                  onClick={() => onChatAbout({ repo: p.repo, path: p.path, commit: commit || null })}>Chat about this</button>
        : null}
    </div>
  );
};
