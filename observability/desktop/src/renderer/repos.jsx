// repos.jsx — Config > Repos: the target repos the engine works on
// (`GET /repos`, #567), each with how many flows are running in it (counted from the
// issues board). The rows come from src/engine-data.js fetchRepoRows. The
// GitHub-side setup check runs only from the head's **Health check** button
// (`onHealthCheck`): index.jsx asks the engine (`POST /repos/setup/check`),
// and each repo's report arrives as a `setup` frame on the event stream,
// applied by applySetupFrame and drawn in the Health check overlay
// (update-overlay.jsx), not on the tiles.
//
// The rows arrive as props through the bridge. Every write goes up through
// one callback, `onAction({ op, repo, … })` -> `{ ok, … } | { error }`, which
// the app sends to main's `engine:repo-action`: add a repo (main answers with the setup
// check), remove it
// (the engine refuses with a 409 naming the running flows while any runs),
// set or remove its GitHub token (a fine-grained PAT for that one repo, sealed
// by the engine; without one nothing can run on the repo), edit its settings in
// the Edit… modal (one tab per setting group, each a page explaining it: the
// engine check, the doctor's switches with this repo's doctor numbers read
// through `onLoadDoctor(since)`, friction bugs, the scan interval weighed against
// GitHub's hourly limit with the webhook secret, and security reviews; #1255),
// create its missing labels. A repo with no webhook or no webhook secret is warned in
// amber on its tile: it is seen only when it is scanned. Access is not a repo's:
// the one engine-wide list of trusted logins sits above the tiles
// (EngineAccessPanel). Which forms are open, what is
// typed into them, and the last add's setup checklist are the only state here.

import { useEffect, useRef, useState } from "react";
import { ModalOverlay } from "./modal-overlay.jsx";
import { Switch } from "./switch.jsx";
import { ConfigureFirstMateModal } from "./first-mate-config.jsx";
import { RefactorPage } from "./refactor-config.jsx";
export { ModalOverlay, inertOutside } from "./modal-overlay.jsx";


/** "src/, engine/ docs" -> ["src/", "engine/", "docs"]: prefixes split on commas and whitespace. */
export const parsePaths = (text) => String(text || "").split(/[\s,]+/).map((p) => p.trim()).filter(Boolean);

/**
 * The setup check as a checklist, one item per question the engine answers:
 * `{ key, ok, text }`, `ok` true (fine), false (needs doing) or null (unknown:
 * check it by hand). An unreadable repo is one failing item: nothing else could be read.
 */
export const setupChecklist = (s) => {
  if (!s) return [];
  if (s.hasToken === false) return [{ key: "readable", ok: false, text: "no token: set one on the repo" }];
  if (!s.readable) return [{ key: "readable", ok: false, text: "the repo's token cannot read this repo" }];
  const items = [{ key: "readable", ok: true, text: "the repo's token can read it" }];
  items.push(s.push === true ? { key: "push", ok: true, text: "the token has push permission" }
    : s.push === false ? { key: "push", ok: false, text: "the token has no push permission" }
    : { key: "push", ok: null, text: "push permission unknown" });
  const missing = Array.isArray(s.missingLabels) ? s.missingLabels : null;
  items.push(!missing ? { key: "labels", ok: null, text: "labels unknown" }
    : missing.length ? { key: "labels", ok: false, text: `missing labels: ${missing.join(", ")}` }
    : { key: "labels", ok: true, text: "every kind label exists" });
  const b = s.defaultBranch;
  if (b && b.exists === false) {
    items.push({ key: "branch", ok: false, text: `default branch ${b.name}: no commits yet, push one` });
  } else if (b) {
    const blocks = [b.requiresReviews && "requires approving reviews", b.forbidsSquash && "forbids squash merges"].filter(Boolean);
    const unknown = b.requiresReviews == null || b.forbidsSquash == null;
    items.push(blocks.length ? { key: "branch", ok: false, text: `${b.name} ${blocks.join(" and ")}: the engine's squash merge is blocked` }
      : unknown ? { key: "branch", ok: null, text: `${b.name}${b.protected ? " is protected" : ""}: GitHub would not say whether it blocks a squash merge` }
      : { key: "branch", ok: true, text: `${b.name} lets the engine squash-merge${b.protected ? " (protected)" : ""}` });
  } else {
    items.push({ key: "branch", ok: null, text: "default branch unknown" });
  }
  const c = s.ciCheck;
  items.push(!c ? { key: "check", ok: null, text: "no engine check configured" }
    : c.ranOnDefaultBranch ? { key: "check", ok: true, text: `check "${c.name}" has run on the default branch` }
    : { key: "check", ok: false, text: `check "${c.name}" never ran on the default branch's last ${c.commitsLooked} commits` });
  const w = s.webhook;
  items.push(!w || w.status === "unknown" ? { key: "webhook", ok: null, text: "webhook unknown: check it by hand" }
    : w.status === "present" ? { key: "webhook", ok: true, text: "a webhook sends issues and check_suite events" }
    : { key: "webhook", ok: false, text: "no webhook sends issues and check_suite events" });
  return items;
};

/**
 * `setups` (lowercased `owner/name` -> `{ setup, setupError, checking, checkedAt }`)
 * with one `setup` frame's `data` applied: `started` marks the repo checking and
 * keeps its report; `done` replaces the report and clears the error; `failed`
 * sets `setupError` to the engine's error text. Any other state, or a frame
 * with no repo, returns `setups` unchanged; only the frame's repo is touched.
 */
export const applySetupFrame = (setups, data) => {
  const m = setups && typeof setups === "object" ? setups : {};
  if (!data || typeof data.repo !== "string" || data.repo === "*") return m;
  const key = data.repo.toLowerCase();
  const was = m[key] || { setup: null, setupError: null, checking: false, checkedAt: null };
  switch (data.state) {
    case "started": return { ...m, [key]: { ...was, checking: true } };
    case "done": return { ...m, [key]: { setup: data.report || null, setupError: null, checking: false, checkedAt: data.checkedAt || null } };
    case "failed": return { ...m, [key]: { ...was, setupError: String(data.error || "the setup check failed"), checking: false } };
    default: return m;
  }
};

/**
 * Why the tile's webhook row is drawn amber, or null, naming the missing half or halves: "no webhook on GitHub"
 * (the last Health check's report found none sending issues and check_suite events) and/or "no webhook secret on the engine"
 * (its deliveries are refused). Either way it waits for
 * its scan interval. A webhook GitHub would not show (`unknown`) is not warned: the checklist says to check it by hand.
 */
export const webhookWarning = (repo, setup) => {
  const missing = !!(setup && setup.webhook && setup.webhook.status === "missing");
  const noSecret = !(repo && repo.hasWebhookSecret);
  if (!missing && !noSecret) return null;
  const why = [missing && "no webhook on GitHub", noSecret && "no webhook secret on the engine"].filter(Boolean).join(" and ");
  const every = repo && Number.isInteger(repo.scanIntervalMinutes) ? ` (every ${repo.scanIntervalMinutes} min)` : "";
  return `${why}: it runs on its scan interval${every} instead`;
};

// GitHub's REST limit for one token (5,000 requests an hour), and the shares of it the scan interval modal warns at.
export const GITHUB_HOURLY_LIMIT = 5000;
export const WARN_PCT = 10;
export const DANGER_PCT = 25;

/**
 * What a repo's scans cost an hour: `callsPerScan × 60 / minutes` requests, as a percent of GITHUB_HOURLY_LIMIT.
 * Over WARN_PCT is `warn`, over DANGER_PCT `danger`; exactly 10 or 25 is the lower band.
 */
export const scanEstimate = (callsPerScan, minutes) => {
  const calls = Number.isFinite(callsPerScan) && callsPerScan > 0 ? callsPerScan : 0;
  const perHour = minutes > 0 ? (calls * 60) / minutes : 0;
  const pct = (perHour / GITHUB_HOURLY_LIMIT) * 100;
  return { perHour, pct, band: pct > DANGER_PCT ? "danger" : pct > WARN_PCT ? "warn" : "ok" };
};

const round = (x) => (x >= 10 ? Math.round(x).toLocaleString("en-US") : String(Math.round(x * 10) / 10));
const minutesOk = (m) => Number.isInteger(m) && m >= 1 && m <= 1440;


// ---- the Doctor tab's numbers (#1001) ---------

// How far back the modal's numbers reach: GET /doctor/runs and /doctor/bugs answer from `since` to now.
export const DOCTOR_DAYS = 7;
export const doctorSince = (now = Date.now()) => new Date(now - DOCTOR_DAYS * 86400000).toISOString();

const sameRepo = (a, b) => typeof a === "string" && typeof b === "string" && a.toLowerCase() === b.toLowerCase();

/**
 * One repo's doctor numbers from every repo's runs (`GET /doctor/runs`) and bugs (`GET /doctor/bugs`), neither of
 * which takes a repo: `runs` (its stuck tickets' doctor runs), `unfixable` and `retries` (by the remedy the doctor
 * picked), `sightings` (its runs that were one), `defects`, the distinct signatures among those sightings (each
 * defect recorded, on a bug or not), and `filed`, those of them already on a doctor bug (the sighting's `bug`, or a
 * listed bug holding the signature).
 */
export const doctorNumbers = (fullName, runs, bugs) => {
  const mine = (runs || []).filter((r) => r && sameRepo(r.repo, fullName));
  const seen = mine.map((r) => r.sighting).filter(Boolean);
  const defects = new Set(seen.map((s) => s.signature).filter(Boolean));
  const onBug = new Set(seen.filter((s) => s.bug).map((s) => s.signature));
  for (const b of bugs || []) for (const g of (b && b.signatures) || []) if (g) onBug.add(g.signature);
  return {
    runs: mine.length,
    unfixable: mine.filter((r) => r.remedy === "unfixable").length,
    retries: mine.filter((r) => r.remedy === "retry").length,
    sightings: seen.length,
    defects: defects.size,
    filed: [...defects].filter((d) => onBug.has(d)).length,
  };
};

/**
 * The modal's numbers: `onLoadDoctor(since)` -> `{ runs: { runs } | { error }, bugs: { bugs } | { error } }`, kept to
 * `fullName`. Resolves to `{ numbers }` or `{ error }`; never rejects.
 */
export const loadDoctorNumbers = async (onLoadDoctor, fullName, now = Date.now()) => {
  if (!onLoadDoctor) return { error: "no doctor numbers in this view" };
  let r;
  try { r = await onLoadDoctor(doctorSince(now)); }
  catch (e) { return { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
  const runs = r && r.runs, bugs = r && r.bugs;
  const errors = [!runs ? "no answer for the doctor's runs" : runs.error, !bugs ? "no answer for the doctor's bugs" : bugs.error].filter(Boolean);
  if (errors.length) return { error: errors.join("; ") };
  return { numbers: doctorNumbers(fullName, runs.runs, bugs.bugs) };
};

/** The other repo engine defects are filed in now (the `GET /repos` row with `engineDefects`), or null. */
export const engineDefectsHolder = (repos, fullName) => {
  const r = (repos || []).find((o) => o && o.engineDefects && !sameRepo(o.fullName, fullName));
  return r ? r.fullName : null;
};


const MARK = { true: "✓", false: "✗", null: "?" };

/** The checklist, with a Create labels button when labels are missing and `run` is given. */
export const SetupChecklist = ({ repo, setup, error, busy, run }) => {
  if (error) return <div className="nmWarn">setup check unavailable: {String(error)}</div>;
  if (!setup) return null;
  const missing = setup.readable && Array.isArray(setup.missingLabels) && setup.missingLabels.length > 0;
  return (
    <ul className="rpChecklist">
      {setupChecklist(setup).map((i) => (
        <li key={i.key} className={"rpItem " + (i.ok === true ? "ok" : i.ok === false ? "bad" : "unknown")}>
          <span className="rpMark">{MARK[String(i.ok)]}</span> {i.text}
          {i.key === "labels" && missing && run &&
            <button className="acBtn" disabled={busy}
              onClick={async () => {
                const r = await run({ op: "labels", repo }, (x) => `created ${(x.created || []).length} label(s) in ${repo}`);
                // The kept report still lists them missing: re-check past the engine's 5 minutes.
                if (r) run({ op: "setup-check", repo }, `re-checking ${repo}'s setup`);
              }}>Create labels</button>}
        </li>
      ))}
    </ul>
  );
};

const TOKEN_HINT = "a fine-grained PAT for this repo: Contents, Issues, Pull requests, Actions read/write";

/**
 * The add form: `owner/name` and the repo's token, in the same step or set on its row afterwards. Main adds it, sets the
 * token, and answers the setup check.
 */
const AddForm = ({ busy, run, onAdded, onClose }) => {
  const [repo, setRepo] = useState("");
  const [token, setToken] = useState("");
  const submit = async (e) => {
    e.preventDefault();
    const name = repo.trim(), t = token.trim();
    const r = await run({ op: "add", repo: name, ...(t ? { token: t } : {}) },
      `added ${name}${t ? " with its token" : ", with no token yet"}`);
    setToken("");
    if (r) { onAdded(name, r); onClose(); }
  };
  return (
    <form className="acForm" onSubmit={submit}>
      <input className="acInput" placeholder="owner/name, e.g. acme/widgets" value={repo} autoFocus
        onChange={(e) => setRepo(e.target.value)} spellCheck={false} />
      <input className="acInput acToken" type="password" autoComplete="off" aria-label="repo token"
        placeholder={`token (nothing runs on the repo without one): ${TOKEN_HINT}`} value={token} onChange={(e) => setToken(e.target.value)} spellCheck={false} />
      <button className="acBtn primary" type="submit" disabled={busy || !/^[\w.-]+\/[\w.-]+$/.test(repo.trim()) || /\s/.test(token.trim())}>Add</button>
      <button className="acBtn" type="button" disabled={busy} onClick={onClose}>Cancel</button>
    </form>
  );
};

/** A fresh webhook secret: 32 random bytes as hex, minted in the app so the user can paste the same value into GitHub. */
const mintSecret = () => Array.from(crypto.getRandomValues(new Uint8Array(32)), (b) => b.toString(16).padStart(2, "0")).join("");


/** Set, replace or remove the repo's token, in a row under it; the engine tries it against GitHub before sealing it. */
const TokenForm = ({ repo, busy, run, onClose }) => {
  const [token, setToken] = useState("");
  const submit = async (e) => {
    e.preventDefault();
    const t = token.trim();
    const r = await run({ op: "token", repo: repo.fullName, token: t }, `${repo.hasToken ? "replaced" : "set"} ${repo.fullName}'s token`);
    setToken("");
    if (r) onClose();
  };
  return (
    <div className="acEdit">
      <form className="acForm" onSubmit={submit}>
        <input className="acInput acToken" type="password" autoComplete="off" autoFocus aria-label="repo token"
          placeholder={`${repo.hasToken ? "replace" : "set"} ${repo.fullName}'s token: ${TOKEN_HINT}`}
          value={token} onChange={(e) => setToken(e.target.value)} spellCheck={false} />
        <button className="acBtn primary" type="submit" disabled={busy || !token.trim() || /\s/.test(token.trim())}>Save</button>
        {repo.hasToken &&
          <button className="acBtn danger" type="button" disabled={busy} title="drop this repo's token; nothing runs on the repo until it has another"
            onClick={async () => { if (await run({ op: "clear-token", repo: repo.fullName }, `${repo.fullName} has no token now`)) onClose(); }}>
            Remove token</button>}
        <button className="acBtn" type="button" disabled={busy} onClick={onClose}>Cancel</button>
      </form>
    </div>
  );
};


// ---- Edit… (#1255): one modal per repo, a tab per setting group ----------------
//
// The tile shows status only; every setting lives on a page of this modal, in
// ModalOverlay (#1252's scrim and panel). Each page has a title, prose saying what
// the setting does (engine/README.md is the source), its controls and its own
// Save, which sends only that page's keys through `send` (Refactoring: through
// its own channel) and keeps the engine's refusal in the page. Switching tabs, Cancel and Escape send nothing.

export const EDIT_TABS = [
  { key: "check", label: "Engine check" },
  { key: "doctor", label: "Doctor" },
  { key: "friction", label: "Friction" },
  { key: "scan", label: "Scanning and webhook" },
  { key: "security", label: "Security review" },
  { key: "docs", label: "Docs path" },
  { key: "refactor", label: "Refactoring" },
];

const n = (x) => x.toLocaleString("en-US");
const sameList = (a, b) => a.length === b.length && a.every((x, i) => x === b[i]);

/** The `check` op for a typed name and textarea: an empty name clears the check and its paths. */
export const checkAction = (fullName, name, pathsText) => {
  const ciCheck = String(name || "").trim() || null;
  return { op: "check", repo: fullName, ciCheck, ciPaths: ciCheck ? parsePaths(pathsText) : [] };
};

/** What a page's controls start at: the repo's row from `GET /repos`. */
export const editValues = (repo) => ({
  name: repo.ciCheck || "",
  paths: (repo.ciPaths || []).join("\n"),
  doctorBugs: !!repo.doctorBugs,
  engineDefects: !!repo.engineDefects,
  frictionBugs: !!repo.frictionBugs,
  minutes: String(repo.scanIntervalMinutes || 60),
  confirming: false,
  securityReview: repo.securityReview !== false,
  docsFirst: !!repo.docsFirst,
  hitlpSurface: !!repo.hitlpSurface,
  childMode: CHILD_MODES.some((m) => m.key === repo.childMode) ? repo.childMode : "serial",
});

/** The child-work modes (engine/README.md, "Split features"), as the Docs path page's choice labels them. */
export const CHILD_MODES = [
  { key: "serial", label: "Serial: one child at a time, a replan between" },
  { key: "parallel", label: "Parallel: children cut to run at the same time" },
  { key: "live", label: "Live: parallel, with the parent running on" },
];

/** The typed interval as a whole number of minutes from 1 to 1440, or NaN. */
const typedMinutes = (text) => (/^\d+$/.test(String(text).trim()) ? Number(String(text).trim()) : NaN);

/**
 * What a page's Save sends: that page's op with only the keys it changed, or null when nothing changed (then
 * nothing is sent). Doctor sends `doctorBugs`, with `engineDefects` only when the page changed it and Doctor bugs
 * stays on (turning Doctor bugs off clears it in the engine).
 */
export const pageAction = (tab, repo, v) => {
  const r = repo.fullName;
  switch (tab) {
    case "check": {
      const a = checkAction(r, v.name, v.paths);
      const was = repo.ciCheck || null;
      return a.ciCheck === was && sameList(a.ciPaths, was ? repo.ciPaths || [] : []) ? null : a;
    }
    case "doctor": {
      const ed = v.doctorBugs && v.engineDefects, wasEd = !!repo.engineDefects;
      if (v.doctorBugs === !!repo.doctorBugs && ed === wasEd) return null;
      return { op: "doctor-bugs", repo: r, doctorBugs: v.doctorBugs, ...(v.doctorBugs && ed !== wasEd ? { engineDefects: ed } : {}) };
    }
    case "friction":
      return v.frictionBugs === !!repo.frictionBugs ? null : { op: "friction-bugs", repo: r, frictionBugs: v.frictionBugs };
    case "scan": {
      const m = typedMinutes(v.minutes);
      return !minutesOk(m) || m === repo.scanIntervalMinutes ? null : { op: "scan-interval", repo: r, scanIntervalMinutes: m };
    }
    case "security":
      return v.securityReview === (repo.securityReview !== false) ? null : { op: "security-review", repo: r, securityReview: v.securityReview };
    case "docs": {
      // Docs-first and the child-work mode go in one op (one PATCH) when either changed; a changed HITLP surface is sent after it.
      const docsChanged = v.docsFirst !== !!repo.docsFirst, modeChanged = v.childMode !== editValues(repo).childMode;
      const docs = !docsChanged && !modeChanged ? null
        : !modeChanged ? { op: "docs-first", repo: r, docsFirst: v.docsFirst }
        : { op: "child-mode", repo: r, childMode: v.childMode, ...(docsChanged ? { docsFirst: v.docsFirst } : {}) };
      const surface = v.hitlpSurface === !!repo.hitlpSurface ? null : { op: "hitlp-surface", repo: r, hitlpSurface: v.hitlpSurface };
      return docs && surface ? { ...docs, then: surface } : docs || surface;
    }
    default: return null;
  }
};

/**
 * Sends one page's action through `send` (an action -> answer); the answer's error stays in the page
 * (`setError`), a success closes the modal. A null action (nothing changed) sends nothing and closes it. An action
 * with `then` (the Docs page's second switch) sends that one after the first succeeded; its refusal is the page's too.
 */
export const savePage = async (send, action, setError, onClose) => {
  setError(null);
  if (!action) { onClose(); return null; }
  let r;
  for (let a = action; a; a = a.then) {
    const { then, ...one } = a;
    try { r = await send(one); }
    catch (e) { r = { error: String(e && e.message ? e.message : e) }; }
    if (!r || r.error) { setError((r && r.error) || "no answer from the main process"); return null; }
  }
  onClose();
  return r;
};

/** The Engine check page's Save, as #1252 had it. */
export const saveCheck = (send, repo, name, paths, setError, onClose) =>
  savePage(send, pageAction("check", repo, { name, paths }), setError, onClose);

/** A page's foot: the engine's refusal, then Cancel and Save, as #1252 placed them. */
const PageFoot = ({ error, busy, onCancel, save = "Save", danger = false, disabled = false }) => (
  <>
    {error && <div className="uoFail">{String(error)}</div>}
    <div className="uoFoot ecFoot">
      <button className="acBtn" type="button" disabled={busy} onClick={onCancel}>Cancel</button>
      <button className={"acBtn " + (danger ? "danger" : "primary")} type="submit" disabled={busy || disabled}>{save}</button>
    </div>
  </>
);

const CheckPage = ({ repo, v, set, busy }) => (
  <>
    <div className="erTitle">Engine check</div>
    <p className="ecProse"><b>Check</b> is the name of a GitHub check run: the CI job the engine waits for before
      it reviews this repo's pull requests. In every flow (chore, bug, feature), after the Smith pushes, "wait for
      the engine check" holds the code review and the security review until a check run with this name has finished
      on the pushed commit. The reviews should not run on code that does not build or whose tests fail; a failing
      check sends the Smith back to the work with the check's log as a finding. The Health check confirms a
      run with this name exists on the default branch's recent commits, so a typo here does not make the engine
      wait on a check that never starts. Leave it empty and the repo has no check: the reviews start right after
      the push.</p>
    <input className="acInput ecField" aria-label="ci check" placeholder="check name (empty: no check)" value={v.name}
      autoFocus disabled={busy} onChange={(e) => set("name", e.target.value)} spellCheck={false} />
    <p className="ecProse"><b>Paths</b> narrows when the engine waits: a list of path prefixes, and the engine waits
      only if the pull request changes a file under one of them. Empty means every path. GitHub workflows commonly
      carry a <code>paths:</code> filter: if the workflow only triggers for <code>engine/**</code>, a pull request
      touching only docs never gets a check run, and the engine would wait until the timeout. So these prefixes
      should mirror the workflow's trigger paths. (In frakworks the check is <code>engine</code> and the paths are{" "}
      <code>engine/</code> and <code>observability/desktop/</code>: one job runs the engine build and the desktop's
      npm test, picking what it needs from the diff.)</p>
    <textarea className="acInput ecField ecPaths" aria-label="ci paths" rows={4} value={v.paths} disabled={busy}
      placeholder={"path prefixes, one per line (empty: every path)"} onChange={(e) => set("paths", e.target.value)} spellCheck={false} />
    <p className="ecProse">Rules of thumb:</p>
    <ul className="ecProse">
      <li>No CI: leave both empty.</li>
      <li>CI on every push: set the name, leave the paths empty.</li>
      <li>A workflow with a <code>paths:</code> filter: set the name and copy those prefixes, one per line.</li>
    </ul>
  </>
);

/** This repo's doctor numbers: `{ numbers }`, `{ error }`, or null while they load. */
const DoctorNumbers = ({ numbers }) => {
  const x = numbers && numbers.numbers;
  return !numbers ? <p className="ecProse acDim">reading this repo's doctor runs…</p>
    : numbers.error ? <div className="nmWarn">this repo's doctor numbers are unavailable: {String(numbers.error)}</div>
    : <p className="ecProse">Here, last {DOCTOR_DAYS} days: {n(x.runs)} doctor run{x.runs === 1 ? "" : "s"}, {n(x.unfixable)} unfixable,
        {" "}{n(x.retries)} retr{x.retries === 1 ? "y" : "ies"}, {n(x.defects)} distinct defect{x.defects === 1 ? "" : "s"}
        {" "}({n(x.filed)} already on a doctor bug) in {n(x.sightings)} sighting{x.sightings === 1 ? "" : "s"}.</p>;
};

const DoctorPage = ({ repo, repos, v, set, busy, numbers }) => {
  const holder = engineDefectsHolder(repos, repo.fullName);
  const turningOff = !!repo.doctorBugs && !v.doctorBugs;
  return (
    <>
      <div className="erTitle">Doctor</div>
      <p className="ecProse">The doctor runs in every target repo whether these switches are on or not. Once a minute it
        looks at every running ticket flow and works out whether it has stopped moving without anyone noticing: an open
        incident, a Smith whose sandbox died and whose exit was never collected, a job the executor never took, a
        Fable-limit stall. For each stuck ticket it starts a doctor run, a sandboxed Smith at the ticket's own tier, which
        diagnoses the position and picks one remedy (healthy, unfixable, retry, collect-exit, run-job); the engine applies
        it. None of that needs a setting: a stuck ticket in this repo gets diagnosed and unstuck either way.</p>
      <p className="ecProse">What the switches decide is what happens with the diagnosis. Every result except "healthy"
        names a defect and where it lives: in the engine (the fleet's own code or infrastructure) or in the target repo
        (this repo's own code, say a build that breaks in a way that leaves the flow stuck).</p>
      <p className="ecProse"><b>Doctor bugs</b>: with it on, a defect in this repo's own code is filed as a bug issue in
        this repo, the stuck ticket gets a comment ("Stuck at … since …; this is owner/name#n"), and repeat sightings are
        counted on the bug (the second ticket comments "Seen again", the third raises the bug's priority, the fourth adds
        needs-review); a bug closed "not planned" is never filed again. With it off, the doctor still unsticks tickets,
        and the sighting is recorded in the engine (the Doctor view, <code>GET /doctor/bugs</code>) but nothing is filed
        or commented on GitHub. Engine-side defects seen on this repo's tickets also need this switch on to be filed
        anywhere.</p>
      <p className="ecProse"><b>Engine defects home</b> marks the one repo in which bugs about the engine itself are
        filed, wherever the stuck ticket was. Exactly one repo in the fleet can hold it, and it needs Doctor bugs on in
        that repo. A defect in the engine seen on a ticket in some other repo is filed here only when that repo has
        Doctor bugs on too; otherwise it is recorded, not filed. If some repo has Doctor bugs on and no repo is marked as
        the home, the engine lists a "sighting" row under What needs you until one is.</p>
      <DoctorNumbers numbers={numbers} />
      <div className="erControls">
        <Switch label="Doctor bugs" checked={v.doctorBugs} disabled={busy} onChange={(x) => set("doctorBugs", x)} />
        <Switch label="Engine defects home" checked={v.doctorBugs && v.engineDefects} disabled={busy || !v.doctorBugs || !!holder}
          onChange={(x) => set("engineDefects", x)} />
        {holder && <div className="acDim">engine defects are filed in {holder}; only one repo can hold this</div>}
        {!holder && !v.doctorBugs && <div className="acDim">Engine defects home needs Doctor bugs on.</div>}
        {turningOff && <div className="acConfirm">Stop filing; bugs already filed stay. Turning Doctor bugs off clears
          Engine defects home here too{repo.engineDefects ? ": engine defects are then filed nowhere until another repo takes them" : ""}.</div>}
      </div>
    </>
  );
};

const FrictionPage = ({ repo, v, set, busy }) => (
  <>
    <div className="erTitle">Friction</div>
    <p className="ecProse">A Smith can leave a friction note during a run: something that got in its way that was not
      the ticket's job to fix (a misleading README line, a missing script, a test that only passes on main). Notes are
      kept by the engine (the Friction view, <code>GET /friction</code>), and Jev, the engine's small judgment model,
      sorts them.</p>
    <p className="ecProse">A note it marks as "two things", meaning the Smith was asked to do its ticket and also fix
      something unrelated, is grouped with the others like it. With this switch on, the engine files one bug per such
      group in the note's repo, which starts authorized runs, so the friction becomes a ticket a Smith can work. The
      note's repo is the repo of the run's ticket, which is why this is a per-repo switch.</p>
    <p className="ecProse">With it off, the notes are kept and shown, and nothing is filed on GitHub. This switch
      concerns what Smiths report about their own runs, not stuck flows: it is independent of the Doctor tab's
      switches.</p>
    <div className="erControls">
      <Switch label="Friction bugs" checked={v.frictionBugs} disabled={busy} onChange={(x) => set("frictionBugs", x)} />
      {!!repo.frictionBugs && !v.frictionBugs && <div className="acConfirm">Stop filing; bugs already filed stay.</div>}
    </div>
  </>
);

/** The webhook secret's status and form: Generate, Save and Remove secret, the refusal kept here. */
const WebhookSecretPanel = ({ repo, busy, send }) => {
  const [secret, setSecret] = useState("");
  const [shown, setShown] = useState(false);
  const [error, setError] = useState(null);
  const act = async (action) => {
    setError(null);
    let r;
    try { r = await send(action); }
    catch (e) { r = { error: String(e && e.message ? e.message : e) }; }
    if (!r || r.error) { setError((r && r.error) || "no answer from the main process"); return; }
    setSecret(""); setShown(false);
  };
  const s = secret.trim();
  return (
    <div className="erControls">
      <div>Webhook secret: {repo.hasWebhookSecret
        ? <span title={repo.webhookSecretSetAt || ""}>set{repo.webhookSecretSetAt ? ` ${String(repo.webhookSecretSetAt).slice(0, 10)}` : ""}</span>
        : <span className="acWarn">none: the engine refuses every delivery</span>}</div>
      <div className="acForm">
        <input className="acInput acToken" type={shown ? "text" : "password"} autoComplete="off" aria-label="webhook secret"
          placeholder={`${repo.hasWebhookSecret ? "replace" : "set"} the webhook secret (the one in its GitHub webhook)`}
          value={secret} disabled={busy} onChange={(e) => setSecret(e.target.value)} spellCheck={false} />
        <button className="acBtn" type="button" disabled={busy} title="fill in a new random secret, shown so you can copy it into the repo's webhook on GitHub"
          onClick={() => { setSecret(mintSecret()); setShown(true); }}>Generate</button>
        <button className="acBtn primary" type="button" disabled={busy || s.length < 16 || /\s/.test(s)}
          onClick={() => act({ op: "webhook-secret", repo: repo.fullName, secret: s })}>
          {repo.hasWebhookSecret ? "Replace webhook secret" : "Set webhook secret"}</button>
        {repo.hasWebhookSecret &&
          <button className="acBtn danger" type="button" disabled={busy} title="drop this repo's webhook secret; its deliveries are refused and it runs on its scan interval"
            onClick={() => act({ op: "clear-webhook-secret", repo: repo.fullName })}>Remove secret</button>}
      </div>
      {error && <div className="uoFail">{String(error)}</div>}
    </div>
  );
};

/** The interval's cost at the typed minutes: this repo, and every repo if they share one token. */
export const scanCost = (repo, repos, minutesText) => {
  const m = typedMinutes(minutesText);
  const valid = minutesOk(m);
  const est = scanEstimate(repo.scanCallsPerScan || 0, valid ? m : repo.scanIntervalMinutes || 60);
  const others = (repos || []).filter((o) => o.fullName !== repo.fullName)
    .reduce((sum, o) => sum + scanEstimate(o.scanCallsPerScan || 0, o.scanIntervalMinutes || 60).perHour, 0);
  const allPerHour = others + est.perHour, allPct = (allPerHour / GITHUB_HOURLY_LIMIT) * 100;
  return { minutes: m, valid, est, allPerHour, allPct, allBand: allPct > DANGER_PCT ? "danger" : allPct > WARN_PCT ? "warn" : "ok" };
};

// The by-hand recipe for the repo's webhook on GitHub (engine/README.md, "A new
// target repo's GitHub setup", its Webhook item, which stays the source of truth):
// an inline panel the Instructions button toggles. GitHub's new-webhook form takes
// no prefill, so the link is to the empty form with the values beside it. It
// shows no secret: the engine never returns one and the secret input is not read.
export const WEBHOOK_EVENTS = ["Issues", "Check suites", "Issue comments", "Pull requests", "Pushes"];

export const WebhookInstructions = ({ repo, open, onToggle }) => (
  <>
    <div className="acForm"><button type="button" className="acBtn" onClick={onToggle}>Instructions</button></div>
    {open && (
      <div className="erControls" role="dialog" aria-label="Create the webhook on GitHub">
        <ol className="ecProse">
          <li>On GitHub, open the repo's Settings → Webhooks → <b>Add webhook</b>:{" "}
            <a href={`https://github.com/${repo.fullName}/settings/hooks/new`} target="_blank" rel="noreferrer">https://github.com/{repo.fullName}/settings/hooks/new</a>.</li>
          <li><b>Payload URL</b>: <code>{"https://<engine-public-host>/hooks/github"}</code>, the Tailscale Funnel path on
            the engine's host. The app does not know that public URL; read the host from the engine's deployment
            (<code>engine/README.md</code>, "When it scans").</li>
          <li><b>Content type</b>: <code>application/json</code>.</li>
          <li><b>Secret</b>: the same value as this repo's webhook secret on the engine. Click <b>Generate</b> in the form
            on this tab, copy the shown value into GitHub's Secret field, then click <b>Set webhook secret</b> (or
            <b> Replace webhook secret</b>), so both sides hold the same value.</li>
          <li><b>Which events</b>: "Let me select individual events", then exactly these five:{" "}
            {WEBHOOK_EVENTS.map((e, i) => <span key={e}>{i ? ", " : ""}<code>{e}</code></span>)}. Issues and Check suites
            wake the scan, Issue comments and Pull requests are the stream's ticket frames the app updates its views
            from, and Pushes is the push frame First mate's clones follow. A hook with fewer events is reported as
            missing by the Health check.</li>
          <li><b>Active</b> checked, then <b>Add webhook</b>; afterwards run the Health check on the <b>Engine check</b> tab
            and confirm the webhook row reads present.</li>
        </ol>
        <div className="acForm"><button type="button" className="acBtn" onClick={onToggle}>Close</button></div>
      </div>
    )}
  </>
);

const ScanPage = ({ repo, repos, v, set, send, busy }) => {
  const [howTo, setHowTo] = useState(false);
  const { minutes, valid, est, allPerHour, allPct, allBand } = scanCost(repo, repos, v.minutes);
  const calls = repo.scanCallsPerScan || 0, c = repo.scanCalls || {};
  const warn = webhookWarning(repo, repo.setup);
  return (
    <>
      <div className="erTitle">Scanning and webhook</div>
      <p className="ecProse">These two are the two halves of one question: how does the engine learn that something
        happened in this repo? The webhook is the push half. GitHub sends the engine <code>issues</code> events (a ticket
        filed, labelled, closed, commented) and <code>check_suite</code> events (the engine check finishing on a Smith's
        push), and the engine acts on each the moment it arrives: a newly authorized ticket starts, a finished check
        releases the reviews waiting on it. The Health check says whether such a webhook exists on GitHub.</p>
      <p className="ecProse">The webhook's deliveries are signed with the secret held here; without a secret the engine
        refuses every delivery, so a webhook without its secret is as good as none. Generate fills in a random secret in
        the clear, to copy into the repo's webhook on GitHub before you save it; the engine seals it and never reads it
        back.</p>
      {warn && <div className="acWarn">⚠ {warn}</div>}
      <WebhookInstructions repo={repo} open={howTo} onToggle={() => setHowTo((o) => !o)} />
      <WebhookSecretPanel repo={repo} busy={busy} send={send} />
      <p className="ecProse">The scan is the fallback half. Every interval the engine lists the repo's issues and open
        checks itself and acts on anything the webhook missed (a delivery lost while the engine was down, a webhook never
        set up). Without a webhook, the scan is the only way the engine sees this repo: a ticket authorized at minute one
        is noticed at the next scan, and a finished engine check is noticed the same way, so every flow in the repo moves
        in steps of the interval. With a webhook working, the scan is a backstop and the interval can be long.</p>
      <p className="ecProse">Each scan spends GitHub API calls from this repo's token; the estimate below is the engine's
        own count from its state, without asking GitHub. The limit is per token, so every repo on one token and the Smith
        runs using it draw on the same {n(GITHUB_HOURLY_LIMIT)} an hour, and a one-minute interval on a busy repo can eat
        the token's hourly rate limit, which is why the engine refuses the field from a Smith. Over {WARN_PCT}% is
        yellow; over {DANGER_PCT}% is red, and leaves the Smiths little room.</p>
      <p className="ecProse">Rule of thumb: webhook present and secret set, leave the interval at the default (60) or
        longer; no webhook, shorten it as far as the call estimate allows.</p>
      <div className="erControls">
        <label className="acForm">Scan every
          <input className="acInput acMinutes" aria-label="scan interval in minutes" inputMode="numeric" disabled={busy}
            value={v.minutes} onChange={(e) => set("minutes", e.target.value)} spellCheck={false} />
          minutes (1 to 1440)</label>
        {!valid && <div className="acProblem">a whole number of minutes from 1 to 1440 (24 hours)</div>}
        <div className="acFormula">
          <div>calls per scan × 60 / interval = <b>{calls}</b> × 60 / <b>{valid ? minutes : "?"}</b> = <b>{round(est.perHour)}</b> requests/hour</div>
          <div className="acDim">per scan: {c.listing || 0} issue listing, {c.authorized || 0} authorized issue(s) read,
            {" "}{c.deferred || 0} deferred issue(s) re-checked, {c.checks || 0} check wait(s) polled</div>
          <div className={"acBand " + est.band}>
            this repo: {round(est.pct)}% of GitHub's {n(GITHUB_HOURLY_LIMIT)}/hour limit</div>
          <div className={"acBand " + allBand}>
            every repo, if they share one token: {round(allPerHour)} requests/hour, {round(allPct)}%</div>
        </div>
        {v.confirming && <span className="acConfirm">over {DANGER_PCT}% of the limit: save anyway?</span>}
      </div>
    </>
  );
};

const SecurityPage = ({ repo, v, set, busy }) => (
  <>
    <div className="erTitle">Security review</div>
    <p className="ecProse">Every chore, bug and feature flow runs two reviews on the Smith's pull request after the engine
      check passes: a code review and a security review, each a separate Smith with its own brief. The security review
      looks for what a code review is not asked to (secrets in the diff, unsafe handling of input, a dependency pulled
      from somewhere new, a workflow change that widens what CI can do) and sends the Smith back with findings like the
      code review does.</p>
    <p className="ecProse">With the switch off, this repo's flows skip the security review and go from the code review
      straight to the merge gate. The engine reads this switch at each push, so turning it off or on reaches a flow
      already running at its next round.</p>
    <p className="ecProse">It is reasonable to turn off in a sandbox or docs-only repo where the review costs a run per
      round for nothing. A Smith cannot change it (the engine answers 403 from a sandbox), so it is always a person's
      decision.</p>
    <div className="erControls">
      <Switch label="Security review" checked={v.securityReview} disabled={busy} onChange={(x) => set("securityReview", x)} />
    </div>
  </>
);

const DocsPage = ({ repo, v, set, busy }) => (
  <>
    <div className="erTitle">Docs path</div>
    <p className="ecProse">In every repo, a push whose changes are all documentation (markdown, anything under a docs
      directory, the READMEs) takes the docs path: after the engine check it goes straight to the code review and the
      merge, with no security review. A push that touches any other file takes the full pipeline.</p>
    <p className="ecProse">With this switch on, the repo is docs-first: the refiner files its asks as chores at a low
      tier, with no design run. It never skips a review by itself; the push still decides, so a code change here is
      reviewed in full. A Smith cannot change it.</p>
    <p className="ecProse">Child work is how a feature that splits into child tickets is cut and worked. Serial is
      today's behaviour: the children are filed one at a time, each followed by a replan. Parallel tells the design
      Smith to cut children that can run at the same time, with dependencies only where one truly needs another
      merged; live is parallel with the parent's session running on while they are worked. Until the engine's
      parallel and live flows land, the two change only how the design Smith cuts the split. A Smith cannot change it.</p>
    <div className="erControls">
      <Switch label="Docs-first" checked={v.docsFirst} disabled={busy} onChange={(x) => set("docsFirst", x)} />
      <label className="erChoice">Child work
        <select className="ecInput" aria-label="Child work" value={v.childMode} disabled={busy} onChange={(e) => set("childMode", e.target.value)}>
          {CHILD_MODES.map((m) => <option key={m.key} value={m.key}>{m.label}</option>)}
        </select>
      </label>
    </div>
    <p className="ecProse">HITLP surface: with this switch on, frakworks facilitates the repo's HITLP requests, so you
      can answer them in the desktop, the web version or on a phone. It gates nothing until part 2 of #2498 lands. A
      Smith cannot change it.</p>
    <div className="erControls">
      <Switch label="HITLP surface" checked={v.hitlpSurface} disabled={busy} onChange={(x) => set("hitlpSurface", x)} />
    </div>
  </>
);

const PAGES = { check: CheckPage, doctor: DoctorPage, friction: FrictionPage, scan: ScanPage, security: SecurityPage, docs: DocsPage };

/**
 * The modal's body, with no state of its own; EditRepoModal holds it. `set(key, value)` changes a control (and
 * `send` is the webhook form's sender too), `error` is the open tab's refusal, `numbers` this repo's doctor numbers.
 * The Refactoring tab (#1943) is its own page with its own channel (refactor-config.jsx): it reads the config
 * when shown and saves through `fleet` (window.fleet unless given), with this modal's footer.
 */
export const EditRepoDialog = ({ repo, repos, tab, onTab, v, set, send, error, busy, numbers, onSave, onCancel, fleet }) => {
  const Page = PAGES[tab] || CheckPage;
  const danger = tab === "scan" && v.confirming;
  return (
    <ModalOverlay label={`Edit: ${repo.fullName}`} onEscape={onCancel} className="erPanel">
      <div className="uoTitle">Edit: {repo.fullName}</div>
      <div className="erTabs" role="tablist">
        {EDIT_TABS.map((t) => (
          <button key={t.key} type="button" role="tab" aria-selected={t.key === tab} className={"erTab" + (t.key === tab ? " on" : "")}
            onClick={() => onTab(t.key)}>{t.label}</button>
        ))}
      </div>
      {tab === "refactor" ? <RefactorPage key={repo.fullName} repo={repo.fullName} onClose={onCancel} Foot={PageFoot} fleet={fleet} />
        : <form className="ecForm erPage" role="tabpanel" onSubmit={(e) => { if (e) e.preventDefault(); onSave(); }}>
          <Page repo={repo} repos={repos} v={v} set={set} send={send} busy={busy} numbers={numbers} />
          <PageFoot error={error} busy={busy} onCancel={onCancel} save={danger ? "Save anyway" : "Save"} danger={danger}
            disabled={tab === "scan" && !minutesOk(typedMinutes(v.minutes))} />
        </form>}
    </ModalOverlay>
  );
};

/**
 * **Edit…**: the tabs' values start at the repo's row; Save sends the open tab's changed keys (over 25% of the
 * hourly limit, the scan tab asks once more: Save anyway). The Doctor tab's numbers are read the first time it is shown.
 */
export const EditRepoModal = ({ repo, repos, busy, send, onLoadDoctor, onClose, initialTab = "check" }) => {
  const [tab, setTab] = useState(initialTab);
  const [v, setV] = useState(() => editValues(repo));
  const [errors, setErrors] = useState({});
  const [numbers, setNumbers] = useState(null);
  const asked = useRef(false), live = useRef(true);
  useEffect(() => () => { live.current = false; }, []);
  useEffect(() => {
    if (tab !== "doctor" || asked.current) return;
    asked.current = true;
    loadDoctorNumbers(onLoadDoctor, repo.fullName).then((r) => { if (live.current) setNumbers(r); });
  }, [tab]);
  const set = (k, x) => setV((o) => ({ ...o, [k]: x, ...(k === "minutes" ? { confirming: false } : {}) }));
  const save = async () => {
    const action = pageAction(tab, repo, v);
    if (tab === "scan" && action && scanCost(repo, repos, v.minutes).est.band === "danger" && !v.confirming) { set("confirming", true); return; }
    await savePage(send, action, (e) => setErrors((m) => ({ ...m, [tab]: e })), onClose);
  };
  return <EditRepoDialog repo={repo} repos={repos} tab={tab} onTab={setTab} v={v} set={set} send={send} error={errors[tab] || null}
    busy={busy} numbers={numbers} onSave={save} onCancel={onClose} />;
};

/** Removing asks first; the engine refuses while flows run, and its reason is the notice. */
const RemoveConfirm = ({ repo, busy, run, onClose }) => (
  <div className="acEdit">
    <div className="acForm">
      <span className="acConfirm">Remove <b>{repo.fullName}</b>? Its grants go with it; its history stays.</span>
      <button className="acBtn danger" disabled={busy}
        onClick={async () => { if (await run({ op: "remove", repo: repo.fullName }, `removed ${repo.fullName}`)) onClose(); }}>Remove</button>
      <button className="acBtn" disabled={busy} onClick={onClose}>Cancel</button>
    </div>
  </div>
);

/**
 * The engine-wide list of trusted logins (`GET /access`): a login on it is
 * trusted on every target repo, the ones added later too; its labels, answers,
 * cancels, retries and First mate actions count. There are no kinds and no
 * per-repo lists. Without `onAction` it draws read-only.
 */
export const EngineAccessPanel = ({ onLoad, onAction }) => {
  const [access, setAccess] = useState(null);
  const [login, setLogin] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null);
  const load = async () => {
    let r;
    try { r = await onLoad(); } catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setAccess(r || { error: "no answer from the main process" });
  };
  useEffect(() => { load(); }, []);
  const act = async (action, done) => {
    setBusy(true); setNotice(null);
    let r;
    try { r = await onAction(action); } catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setBusy(false);
    if (!r || r.error) { setNotice({ ok: false, text: (r && r.error) || "no answer from the main process" }); return null; }
    setNotice({ ok: true, text: done });
    load();
    return r;
  };
  const logins = (access && access.access && access.access.logins) || [];
  return (
    <section className="acSetting rpEngineAccess">
      <div className="acSettingHead"><span className="acName">Trusted logins</span>
        <span className="acDim">whose labels and actions the engine honors, on every target repo</span></div>
      {notice && <div className={notice.ok ? "acNotice" : "err"}>{notice.text}</div>}
      {!access ? <div className="acEdit acDim">reading trusted logins…</div>
        : access.error ? <div className="acEdit err">{String(access.error)}</div>
        : <div className="acEdit rpAccess">
            {!logins.length
              ? <div className="acDim">no trusted logins: no `authorized` label counts</div>
              : <ul className="rpGrants">
                  {logins.map((l) => (
                    <li key={l.login}>
                      {l.login}
                      {onAction && <button className="acBtn danger" disabled={busy}
                        onClick={() => act({ op: "remove", login: l.login }, `removed ${l.login}`)}>Remove</button>}
                    </li>
                  ))}
                </ul>}
            {onAction &&
              <form className="acForm" onSubmit={(e) => {
                e.preventDefault();
                const l = login.trim();
                act({ op: "add", login: l }, `trusted ${l}`).then((r) => { if (r) setLogin(""); });
              }}>
                <input className="acInput" placeholder="GitHub login" value={login} onChange={(e) => setLogin(e.target.value)} spellCheck={false} />
                <button className="acBtn primary" type="submit" disabled={busy || !login.trim()}>Add</button>
              </form>}
          </div>}
    </section>
  );
};

/**
 * `result` is what main's `engine:repos` returned: src/engine-data.js
 * fetchRepos' `{ repos, runningError }` or `{ error }`, drawn as the shared
 * .err block. Without `onAction` the view draws read-only, with no controls.
 */
/** **Health check**: the repos' names in page order to `onHealthCheck`, which sends the one request and opens the overlay. */
export const HealthCheckButton = ({ repos, loading, checking, onHealthCheck }) => (
  <button className="acBtn" aria-label="Health check" title="Check every repo's GitHub-side setup now"
    disabled={loading || !repos.length || checking}
    onClick={() => onHealthCheck(repos.map((r) => r.fullName))}>Health check</button>
);

// What the page notice names after a save from the Edit… modal.
const SAVED = { check: "check", "doctor-bugs": "doctor switches", "friction-bugs": "friction bugs", "scan-interval": "scan interval",
  "security-review": "security review", "webhook-secret": "webhook secret", "clear-webhook-secret": "webhook secret (removed)" };

export const Repos = ({ result, onRefresh, refreshing, onAction, onLoadEngineAccess, onEngineAccessAction, onLoadDoctor, onHealthCheck, checking = false }) => {
  const { repos = [], runningError = null, error } = result || {};
  // { kind: "add" } | { kind: "edit" | "firstMate" | "token" | "remove", repo } | null
  const [editing, setEditing] = useState(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null); // { ok, text }
  const [added, setAdded] = useState(null); // { repo, setup, setupError }: the last add's checklist
  const close = () => setEditing(null);
  // Send one action; say how it went; refresh the list after any success. `done` is the notice, or a function of the answer.
  const run = async (action, done) => {
    setBusy(true); setNotice(null);
    let r;
    try { r = await onAction(action); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setBusy(false);
    if (!r || r.error) { setNotice({ ok: false, text: (r && r.error) || "no answer from the main process" }); return null; }
    setNotice({ ok: true, text: typeof done === "function" ? done(r) : done });
    if (onRefresh) onRefresh();
    return r;
  };
  // The same, with no page notice on a refusal: the Edit… modal shows it inside the open tab.
  const send = async (action) => {
    setBusy(true); setNotice(null);
    let r;
    try { r = await onAction(action); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setBusy(false);
    if (!r || r.error) return { error: (r && r.error) || "no answer from the main process" };
    setNotice({ ok: true, text: `saved ${action.repo}'s ${SAVED[action.op] || "settings"}` });
    if (onRefresh) onRefresh();
    return r;
  };
  const toggle = (kind, repo) => {
    setNotice(null);
    if (editing && editing.kind === kind && editing.repo === repo) { close(); return; }
    setEditing({ kind, repo });
  };
  const head = (
    <div className="head">
      <h1>Repos{repos.length ? ` · ${repos.length}` : ""}</h1>
      <span className="meta">
        {onAction && !error &&
          <button className="acBtn" disabled={busy || (editing && editing.kind === "add")}
            onClick={() => { setNotice(null); setEditing({ kind: "add" }); }}>+ Add repo</button>}
        {onHealthCheck && !error &&
          <HealthCheckButton repos={repos} loading={!!(result && result.loading)} checking={checking} onHealthCheck={onHealthCheck} />}
        <button className={"refreshBtn" + (refreshing ? " spinning" : "")} title="Refresh now"
          disabled={refreshing} onClick={() => onRefresh && onRefresh()}>⟳</button>
      </span>
    </div>
  );
  const noticeEl = notice && <div className={notice.ok ? "acNotice" : "err"}>{notice.text}</div>;
  if (error) return <div className="acView">{head}{noticeEl}<div className="err">{String(error)}</div></div>;
  const is = (kind, repo) => editing && editing.kind === kind && editing.repo === repo;
  return (
    <div className="acView">
      {head}
      {noticeEl}
      {onLoadEngineAccess && <EngineAccessPanel onLoad={onLoadEngineAccess} onAction={onAction ? onEngineAccessAction : null} />}
      {editing && editing.kind === "add" &&
        <AddForm busy={busy} run={run} onClose={close}
          onAdded={(repo, r) => setAdded({ repo, setup: r.setup || null, setupError: r.setupError || null })} />}
      {added &&
        <section className="acSetting">
          <div className="acSettingHead">
            <span className="acName">{added.repo}: setup check</span>
            <button className="acBtn" onClick={() => setAdded(null)}>Dismiss</button>
          </div>
          <SetupChecklist repo={added.repo}
            setup={(repos.find((r) => r.fullName.toLowerCase() === added.repo.toLowerCase()) || {}).setup || added.setup}
            error={added.setupError} busy={busy} run={onAction ? run : null} />
        </section>}
      {runningError && <div className="nmWarn">running-flow counts unavailable: {runningError}</div>}
      {!repos.length
        ? <div className="nmEmpty">{result && result.loading ? "asking the engine…"
            : onAction ? "The engine has no target repos yet. Add one above." : "The engine has no target repos yet."}</div>
        : <div className="acGrid">
            {repos.map((r) => {
              const warn = webhookWarning(r, r.setup);
              return (
                <article key={r.fullName} className="acTile rpTile">
                  <header className="acTileHead">
                    <span className="acName">{r.fullName}</span>
                  </header>
                  <div className="acTileBody">
                    <div className="acWindow"><span className="acWinLabel acWinLabelWide">github</span>
                      <a href={`https://github.com/${r.fullName}`} target="_blank" rel="noreferrer">https://github.com/{r.fullName}</a></div>
                    <div className="acWindow"><span className="acWinLabel acWinLabelWide">token</span>
                      {r.hasToken ? <span title={r.tokenSetAt ? `set ${r.tokenSetAt}` : ""}>set{r.tokenSetAt ? ` ${String(r.tokenSetAt).slice(0, 10)}` : ""}</span>
                        : <span className="acDim">none: nothing can run here until one is set</span>}</div>
                    <div className="acWindow"><span className="acWinLabel acWinLabelWide">webhook</span>
                      {warn
                        ? <span className="acWarn">⚠ {warn}</span>
                        : <span title={r.webhookSecretSetAt ? `set ${r.webhookSecretSetAt}` : ""}>secret set{r.webhookSecretSetAt ? ` ${String(r.webhookSecretSetAt).slice(0, 10)}` : ""}</span>}</div>
                    <div className="acWindow"><span className="acWinLabel acWinLabelWide">running</span>
                      <span className="rpRunning">{r.running == null ? "–" : `${r.running} flow${r.running === 1 ? "" : "s"}`}</span></div>
                  </div>
                  <footer className="acTileFoot">
                    <div className="acFacts"><span className="acAge" title={r.addedAt || ""}>added {String(r.addedAt || "").slice(0, 10) || "–"}</span></div>
                    <div className="acActions">
                      {onAction && <>
                        <button className="acBtn" disabled={busy} onClick={() => toggle("edit", r.fullName)}>Edit…</button>
                        <button className="acBtn" disabled={busy} onClick={() => toggle("firstMate", r.fullName)}>Configure First Mate</button>
                        <button className="acBtn" disabled={busy} onClick={() => toggle("token", r.fullName)}>{r.hasToken ? "Replace token" : "Set token"}</button>
                        <button className="acBtn danger" disabled={busy} onClick={() => toggle("remove", r.fullName)}>Remove</button>
                      </>}
                    </div>
                    {is("edit", r.fullName) &&
                      <EditRepoModal repo={r} repos={repos} busy={busy} send={send} onLoadDoctor={onLoadDoctor} onClose={close} />}
                    {is("firstMate", r.fullName) && <ConfigureFirstMateModal repo={r.fullName} onClose={close} />}
                    {is("token", r.fullName) && <TokenForm repo={r} busy={busy} run={run} onClose={close} />}
                    {is("remove", r.fullName) && <RemoveConfirm repo={r} busy={busy} run={run} onClose={close} />}
                  </footer>
                </article>
              );
            })}
          </div>}
    </div>
  );
};
