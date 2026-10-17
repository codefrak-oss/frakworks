// status-bar.jsx — the strip along the bottom of the window, after VS Code's:
// which commit the checkout is on, which one the app is running, whether the
// checkout or origin/main has moved past it and whether that matters to the
// app, the engine's reachability, and the one control that pulls, rebuilds
// and restarts. Drawing only: `version` is what src/app-version.js reported
// through the bridge; `engine` is what the last issues poll learned.
//
// `version.cmp.stale` is the flag that lights the button: the checkout, or
// origin/main (fetched by main every minute since 2026-09-26; before that
// nothing was fetched), has commits the running app lacks AND they touched
// observability/desktop/. Changes that only touch engine/ are shown, in grey,
// and do not nag. A failed fetch keeps the local facts and names the error on
// hover. The fetch and the pull are main's; this file never touches git.
//
// Next to Restart, the engine's build and the Update engine button
// (src/engine-update.js): lit only when the registry's frakworks-engine:main
// digest differs from the running engine's; an unknown on either side is
// drawn `sb-bad` with the reason on hover, never guessed. The click is main's
// (a fresh check, a confirm naming the live runs, then pull and up); this file
// only draws what it reported.

import { streamText, RESTARTING } from "./engine-events.js";

const short = (s) => (s || "").slice(0, 7);

/** The version phrase; mirrors src/app-version.js `describe`, which is the tested one. */
export const versionText = (v) => {
  if (!v) return { text: "version…", level: "muted" };
  if (v.packaged) return packagedText(v);
  if (v.error) return { text: v.error, level: "muted" };
  let text = `${v.branch} · ${short(v.sha)}`;
  if (v.dirty) text += " · dirty";
  const c = v.cmp;
  if (!c) return { text: `${text} · checking…`, level: "muted" };
  if (c.error) return { text: `${text} · ${c.error}`, level: "muted" };
  const o = c.origin && !c.origin.error && c.origin.behind ? c.origin : null;
  const behind = o
    ? ` · behind origin ${o.behind} (${o.stale ? "desktop changed" : (o.changed || []).join(", ") || "no files"}${o.blocked ? `; won't pull: ${o.blocked}` : ""})`
    : "";
  const level = c.stale ? "stale" : o ? "muted" : "ok";
  if (!c.ahead && v.sha === v.running) return { text: `${text} · running${behind}`, level };
  const what = c.desktop ? "desktop changed" : (c.changed || []).join(", ") || "no files";
  const n = c.ahead ? ` +${c.ahead}` : "";
  return { text: `${text} · running ${short(v.running)}${n} (${what})${behind}`, level: c.stale ? "stale" : "muted" };
};

/**
 * The packaged build's phrase; mirrors src/app-update.js `describeUpdate`,
 * which is the tested one. No checkout: the version is package.json's and
 * "newer" is the feed's latest-mac.yml. "ready" lights the button.
 */
export const packagedText = (v) => {
  const base = `v${v.version}`;
  const u = v.update || {};
  if (u.downloaded) return { text: `${base} · v${u.downloaded.version} ready`, level: "stale" };
  if (u.available) return { text: `${base} · downloading v${u.available.version}…`, level: "muted" };
  if (u.error) return { text: `${base} · running · feed: ${u.error}`, level: "muted" };
  if (u.checking && !u.checkedAt) return { text: `${base} · checking…`, level: "muted" };
  return { text: `${base} · running`, level: "ok" };
};

/** The phrase's hover: both full SHAs, origin/main's, and the fetch error if the last fetch failed; for a packaged build, the feed and what it said. */
export const versionTitle = (v) => {
  if (v && v.packaged) {
    const u = v.update || {};
    const lines = [`running  v${v.version}`, `feed     ${u.feed || "(build default)"}`];
    if (u.checkedAt) lines.push(`checked  ${new Date(u.checkedAt).toISOString()}`);
    if (u.available) lines.push(`available v${u.available.version}`);
    if (u.downloaded) lines.push(`downloaded v${u.downloaded.version}`);
    if (u.error) lines.push(`error    ${u.error}`);
    return lines.join("\n");
  }
  if (!v || !v.sha) return "";
  const lines = [`checkout ${v.sha}`, `running  ${v.running || "?"}`];
  const o = v.cmp && v.cmp.origin;
  if (o && o.error) lines.push(`origin/main unknown: ${o.error}`);
  else if (o && o.sha) lines.push(`origin   ${o.sha}`);
  return lines.join("\n");
};

const engineBuild = (b) => (b && b.commit ? `sha-${short(b.commit)}` : b && b.digest ? b.digest.slice(7, 19) : "?");
const DIGEST = /^sha256:[0-9a-f]{64}$/;

const behindText = (e) => (!e || e.behind == null ? "" : ` · ${e.behind} commit${e.behind === 1 ? "" : "s"} behind`);

/** An engine not on this machine, from its GET /engine/update; mirrors src/engine-update.js `describeEngineDeploy`. */
const engineDeployText = (s, run) => {
  const e = s.engine;
  if (!e) return { text: `${run} · deploy status unknown`, level: "bad", lit: false, remote: true, reason: s.engineError || "no answer from GET /engine/update" };
  if (!e.selfUpdate) return { text: `${run} · its host updates it`, level: "muted", lit: false, remote: true, reason: e.error || "" };
  if (!e.latest || !e.latest.commit) return { text: e.error ? `${run} · main unknown: ${cutError(e.error)}` : `${run} · main unknown`, level: "bad", lit: false, remote: true, reason: e.error || "the engine could not read the registry's :main" };
  if (!e.updateAvailable) return { text: `${run} · main`, level: "ok", lit: false, remote: true, reason: "" };
  return { text: `${run} · main ${engineBuild(e.latest)}${behindText(e)}`, level: "stale", lit: true, remote: true, reason: "" };
};

/** The engine's build phrase and the button's state; mirrors src/engine-update.js `describeEngineUpdate`, the tested one. */
export const engineText = (s) => {
  if (!s) return { text: "engine build…", level: "muted", lit: false, reason: "" };
  if (s.updating) return { text: `engine ${engineBuild(s.running)} · updating: ${s.updating}…`, level: "muted", lit: false, reason: "" };
  if (!s.checkedAt) return { text: "engine build checking…", level: "muted", lit: false, reason: "" };
  const run = `engine ${engineBuild(s.running)}`;
  if (s.remote) return engineDeployText(s, run);
  if (s.runningError) return { text: `${run} · build unknown`, level: "bad", lit: false, reason: s.runningError };
  if (s.availableError) return { text: `${run} · main unknown: ${cutError(s.availableError)}`, level: "bad", lit: false, reason: s.availableError };
  const r = s.running && s.running.digest, a = s.available && s.available.digest;
  if (!DIGEST.test(r || "") || !DIGEST.test(a || "")) return { text: `${run} · digest unknown`, level: "bad", lit: false, reason: "a digest is missing; not guessing" };
  if (r === a) return { text: `${run} · main`, level: "ok", lit: false, reason: "" };
  return { text: `${run} · main ${engineBuild(s.available)}`, level: "stale", lit: true, reason: "" };
};

/** The Update engine button's hover; mirrors src/engine-update.js `engineUpdateTitle`. */
export const engineTitle = (s) => {
  if (!s) return "";
  const b = (x) => (x ? `${x.commit || "commit ?"} ${x.digest || "digest ?"}` : "?");
  const d = engineText(s);
  if (d.remote) {
    const e = s.engine || {};
    const lines = [`running   ${b(s.running)}`, `available ${b(e.latest)}`, `image     ${e.image || "?"}`,
      `mode      ${e.mode || "?"}`, `checked   ${s.checkedAt ? new Date(s.checkedAt).toISOString() : "not yet"}`];
    if (e.behind != null) lines.push(`behind    ${e.behind} commits`);
    if (s.liveRuns != null) lines.push(`live runs ${s.liveRuns}`);
    if (e.openSessions != null) lines.push(`sessions  ${e.openSessions} first-mate`);
    if (d.reason) lines.push(`error     ${d.reason}`);
    lines.push(d.lit ? "Click to deploy main: the engine restarts itself (asks first)" : "Click to check now");
    return lines.join("\n");
  }
  const lines = [
    `running   ${b(s.running)}`,
    `available ${b(s.available)}`,
    `registry  ${s.ref || ""}`,
    `checked   ${s.checkedAt ? new Date(s.checkedAt).toISOString() : "not yet"}`,
  ];
  if (s.liveRuns != null) lines.push(`live runs ${s.liveRuns}`);
  if (d.reason) lines.push(`error     ${d.reason}`);
  if (d.lit) lines.push(...engineChangesLines(s.changes));
  lines.push(d.lit ? "Click to pull main and recreate the engine (asks first)" : "Click to check the registry now");
  return lines.join("\n");
};

/** What the update brings, for the hover; mirrors src/engine-update-changes.js `changesLines`. */
export const engineChangesLines = (c, max = 15) => {
  if (!c) return [];
  if (c.error) return [`The PR list is missing: ${c.error}`];
  const list = c.changes || [];
  if (!list.length) return ["No PRs merged between the two commits."];
  const lines = [`${list.length} PR${list.length === 1 ? "" : "s"} merged:`];
  for (const x of list.slice(0, max)) lines.push(`  #${x.pr} ${x.title}${x.tickets.length ? ` (closes ${x.tickets.map((t) => `#${t.n}${t.title ? ` ${t.title}` : ""}`).join(", ")})` : ""}`);
  if (list.length > max) lines.push(`  … and ${list.length - max} more`);
  return lines;
};

/** The line after a click; mirrors src/engine-update.js `reportUpdate`. */
/** An error as the bar's text carries it: at most 80 characters, cut with `…` (engine-update.js, cutError). */
const cutError = (e) => { const t = String(e); return t.length > 80 ? `${t.slice(0, 80)}…` : t; };

export const engineReport = (r) => {
  if (r && r.started) return { text: "engine started (left in Created)", level: "ok", detail: `docker start ${r.started}` };
  if (!r || r.ok == null) return null;
  if (r.ok) {
    const b = r.build || {};
    return { text: `engine updated to ${engineBuild(b)}`, level: "ok", detail: `commit ${b.commit || "?"}\ndigest ${b.digest || "?"}` };
  }
  const failed = (r.steps || []).find((s) => !s.ok);
  const text = !failed ? "engine update failed" : failed.step === "check" && failed.detail ? `engine check failed: ${cutError(failed.detail)}` : `engine ${failed.step} failed`;
  return { text, level: "bad", detail: failed ? failed.detail : "" };
};

/**
 * `updating` is the in-flight state: null, or { steps } while it runs, or the
 * result { ok, steps } after a failure (a success relaunches, so it is never
 * drawn). The button fast-forwards the checkout to origin/main when it is
 * behind (main refuses when it is off main, dirty or diverged, and says so),
 * rebuilds what is on disk and relaunches — lit when the checkout or
 * origin/main has desktop changes the running app lacks; otherwise the way a
 * local edit is tried.
 */
// `stream` is the engine's event stream's state for the selected repo
// (renderer/engine-events.js useStreamState): `● live`, `● catching up`, or
// `○ down · <age since the last frame>`, beside the engine's commit.
// `restarting` is an engine update in flight (the overlay's progress, #1053):
// the engine dot and the stream read "restarting for update" in the muted
// colour instead of red until the update ends, either way.

/** The engine dot's phrase and level: `● url`, `○ url` (red), `· url`, or the update's restart, muted. */
export const engineDot = (engine, restarting = false) => {
  const url = engine && engine.url ? engine.url.replace(/^https?:\/\//, "") : "";
  if (restarting) return { text: `engine ◌ ${RESTARTING}${url ? ` ${url}` : ""}`, level: "muted", title: "An engine update is in flight; the engine restarts as part of it" };
  if (engine && engine.ok) return { text: `engine ● ${url}`, level: "ok", title: "" };
  if (engine && engine.ok === false) return { text: `engine ○ ${url}`, level: "bad", title: engine.error || "" };
  return { text: `engine · ${url}`, level: "muted", title: (engine && engine.error) || "" };
};

export const StatusBar = ({ version, engine, updating, onRestart, engineUpdate, engineResult, engineBusy, onUpdateEngine, stream = null, now = Date.now(), restarting = false }) => {
  const v = versionText(version);
  const lit = !!(version && (version.packaged ? version.update && version.update.downloaded : version.cmp && version.cmp.stale));
  const busy = updating && !("ok" in updating);
  const failed = updating && updating.ok === false;
  const failedStep = failed ? updating.steps.find((s) => !s.ok) : null;
  const e = engineText(engineUpdate);
  const report = engineReport(engineResult);
  const st = streamText(stream, now, restarting);
  const dot = engineDot(engine, restarting);
  return (
    <div className="statusBar">
      <span className={"sbVersion sb-" + v.level}
            title={versionTitle(version)}>
        <span className="sbBranchIcon">{version && version.packaged ? "⬇" : "⎇"}</span> {v.text}
      </span>
      {failed && <span className="sbErr" title={failedStep ? failedStep.detail : ""}>{failedStep ? `${failedStep.step} failed` : "restart failed"}</span>}
      <span className="sbSpacer" />
      <span className={"sbEngine sb-" + dot.level} title={dot.title}>{dot.text}</span>
      <span className={"sbEngineBuild sb-" + e.level} title={engineTitle(engineUpdate)}>{e.text}</span>
      <span className={"sbStream sb-" + st.level}
            title={stream ? `${stream.repo || ""} · ${stream.state}${stream.lastFrameAt ? ` · last frame ${new Date(stream.lastFrameAt).toISOString()}` : ""}` : "the event stream's state is not known yet"}>{st.text}</span>
      {report && <span className={report.level === "bad" ? "sbErr" : "sb-ok"} title={report.detail}>{report.text}</span>}
      <button className={"sbButton" + (e.lit ? " lit" : "")} disabled={!!engineBusy || !!(engineUpdate && engineUpdate.updating)}
              title={engineTitle(engineUpdate)}
              onClick={() => onUpdateEngine && onUpdateEngine()}>
        {engineBusy || (engineUpdate && engineUpdate.updating) ? `${(engineUpdate && engineUpdate.updating) || "checking"}…` : "⇪ Update engine"}
      </button>
      <button className={"sbButton" + (lit ? " lit" : "")} disabled={busy}
              title={version && version.packaged
                ? (lit ? `Install v${version.update.downloaded.version} and relaunch` : "Check the release feed for a newer build, then relaunch")
                : version && version.cmp && version.cmp.origin && version.cmp.origin.behind
                ? "Pull origin/main (fast-forward only), rebuild and relaunch" : "Rebuild from the checkout and relaunch"}
              onClick={() => onRestart && onRestart()}>
        {busy ? `${updating.steps.length ? updating.steps[updating.steps.length - 1].step + "…" : "restarting…"}` : "⟳ Restart"}
      </button>
    </div>
  );
};
