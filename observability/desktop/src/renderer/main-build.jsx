// main-build.jsx — the merged PR's main build, a strip right after the ticket
// detail's progress bar (#100090, option B). The build is the `images` run
// triggered by `main` whose `headSha` is the merged PR's merge commit
// (`prs[].mergeSha`, the engine's `mergeCommitSha`), read through
// window.fleet.ciRuns (GET /repos/{owner}/{name}/ci/runs) on open and every
// 15 s, as Analytics > Builds does. The detail hands over its 1 s `now`.
import { useEffect, useRef, useState } from "react";
import { elapsedLabel } from "./build-strip.jsx";
import { pollEvery } from "./builds.jsx";

const POLL_MS = 15000;
const RUNS = 50;

/** The merged PR's merge commit sha, or null (no PR, not merged, no sha). */
export const mergedSha = (prs) => {
  const pr = (Array.isArray(prs) ? prs : []).find((p) => p && p.state === "MERGED" && typeof p.mergeSha === "string" && p.mergeSha);
  return pr ? pr.mergeSha : null;
};

/** The `images` run on `main` for `sha`, or null. */
export const matchMainBuild = (runs, sha) => {
  if (!sha || !Array.isArray(runs)) return null;
  return runs.find((r) => r && r.area === "images" && r.trigger === "main" && r.headSha === sha) || null;
};

/** The strip's `{ state, text }`: not started, queued/running with a live m:ss, or the outcome with its duration. */
export const mainBuildLabel = (run, now) => {
  if (!run) return { state: "none", text: "Main build (images) not started" };
  if (run.status !== "completed") {
    const since = Date.parse(run.startedAt || run.createdAt);
    const secs = Number.isFinite(since) ? (now - since) / 1000 : 0;
    const state = run.status === "in_progress" ? "running" : "queued";
    return { state, text: `Main build (images) ${state} ${elapsedLabel(secs)}` };
  }
  let secs = run.durationSeconds == null ? NaN : Number(run.durationSeconds);
  if (!Number.isFinite(secs)) {
    const a = Date.parse(run.startedAt), b = Date.parse(run.completedAt);
    secs = Number.isFinite(a) && Number.isFinite(b) ? (b - a) / 1000 : 0;
  }
  const outcome = run.conclusion || "completed";
  return { state: outcome === "success" ? "success" : outcome === "failure" ? "failure" : "other",
    text: `Main build (images) ${outcome} in ${elapsedLabel(secs)}` };
};

/** Drawing only: `result` is `{ loading }`, `{ error }` or `{ runs }`. */
export function MainBuildView({ sha, result, now }) {
  if (!sha) return null;
  const short = sha.slice(0, 7);
  if (!result || result.loading) return <div className="mainBuild mbNone muted">Main build (images): reading… — merge {short}</div>;
  if (result.error) return <div className="mainBuild mbError err">Main build (images) could not be read: {String(result.error)}</div>;
  const run = matchMainBuild(result.runs, sha);
  const { state, text } = mainBuildLabel(run, now);
  return (
    <div className={`mainBuild mb-${state}`}>
      {run && run.url
        ? <a href={run.url} target="_blank" rel="noreferrer" title="The run on GitHub">{text} — merge {short} ↗</a>
        : <span>{text} — merge {short}</span>}
    </div>
  );
}

/** The strip: nothing without a merged PR's sha; else reads the repo's runs on open and every 15 s. */
export function MainBuildStrip({ repo, prs, now }) {
  const sha = mergedSha(prs);
  const [result, setResult] = useState({ loading: true });
  const seq = useRef(0);
  useEffect(() => {
    if (!sha || !repo) return undefined;
    const load = async () => {
      const mine = ++seq.current;
      if (!(typeof window !== "undefined" && window.fleet && window.fleet.ciRuns)) {
        setResult({ error: "this build has no Builds channel" });
        return;
      }
      let r;
      try { r = await window.fleet.ciRuns(repo, RUNS, null, null); }
      catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
      if (mine !== seq.current) return;
      setResult(r && Array.isArray(r.runs) ? { runs: r.runs } : { error: (r && r.error) || "no response from the main process" });
    };
    setResult({ loading: true });
    load();
    const stop = pollEvery(load, POLL_MS);
    return () => { seq.current++; stop(); };
  }, [repo, sha]);
  return <MainBuildView sha={sha} result={result} now={now} />;
}
