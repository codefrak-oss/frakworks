// accounts.jsx — Config > AI Accounts: the accounts the engine bills Smiths
// to (`GET /accounts`, engine/README.md "AI accounts"), each with its plan
// meter (`GET /accounts/usage`, "The usage meter") joined on in
// src/engine-data.js, and a badge of the Smith runs live on it now per model
// (`running` on each `GET /accounts` row), absent when none is.
//
// The rows arrive as props through the bridge. Every write goes up through
// one callback, `onAction({ op, name, … })` -> `{ ok } | { error }`, which the
// app sends to main's `engine:account-action`: add an account, set or replace
// its credential, verify the stored one, enable or disable it, remove it,
// and, on an anthropic tile, refresh its meter (the engine asks Anthropic).
// On Config > AI Accounts a tile's one control is Edit…, a tabbed modal
// (EditAccountDialog, built like Config > Repos' Edit…) holding every
// per-account action; its refusals stay in the open tab. A
// jev account (TypeSafe's Jev, never a Smith's) has no meter: its tile says
// "no usage data" in place of the windows. The open forms and what
// is typed into them, and a one-second clock for the reset countdowns, are
// the only state kept here; a credential is cleared
// from it as soon as it has been sent and is never drawn back.
//
// The intake refiner's tier is not here: it has its own Config page,
// refiner-tier.jsx (#594).

import { useEffect, useState } from "react";
import { fmtAgo } from "./issues.jsx";
import { credentialProblem } from "../credential-rules.js";
import { ModalOverlay } from "./modal-overlay.jsx";
import { Switch } from "./switch.jsx";

export const PROVIDERS = ["anthropic", "openai", "jev", "codex"];

// Where each provider's credential comes from, as a hint in the form.
const TOKEN_HINT = {
  anthropic: "sk-ant-oat01-… (claude setup-token)", openai: "sk-… (OpenAI API key)",
  jev: "TypeSafe API key (console.typesafe.ai/keys)",
};

// Providers with no usage meter: Jev has no balance endpoint, and Codex
// sign-in is not connected yet.
export const NO_METER = new Set(["jev", "codex"]);

// 0.48 -> "48%"; the meter reports utilization as a fraction.
export const fmtPct = (x) => (typeof x === "number" && Number.isFinite(x) ? `${Math.round(x * 100)}%` : "–");

/** One phrase for whether a Smith can run on this account right now. */
export const accountState = (a) => {
  if (!a.enabled) return { key: "off", text: "disabled" };
  if (a.provider === "codex") return a.hasCredential
    ? { key: "stored", text: "login stored · Smith runs not connected" }
    : { key: "nocred", text: "sign-in not connected" };
  if (!a.hasCredential) return { key: "nocred", text: "no credential" };
  if (a.usage && a.usage.rejected) return { key: "rejected", text: "rate-limited" };
  return { key: "ready", text: "ready" };
};

/** "claude-opus-5-5" -> "opus", and likewise sonnet, fable, haiku; any other id as it is. */
export const shortModel = (id) => {
  const m = /^(?:claude-)?(opus|sonnet|fable|haiku)\b/.exec(String(id || ""));
  return m ? m[1] : String(id || "");
};

/**
 * The live-runs badge's text from `GET /accounts`' `running` ([{ model, count }]):
 * "2 opus · 1 sonnet", by count descending then name, ids with one short name
 * added together. null when nothing is live (or the engine sent no list): no badge.
 */
export const runningText = (running) => {
  if (!Array.isArray(running)) return null;
  const by = new Map();
  for (const r of running) {
    const n = r && Number.isFinite(r.count) ? r.count : 0;
    if (n > 0) { const k = shortModel(r.model); by.set(k, (by.get(k) || 0) + n); }
  }
  if (!by.size) return null;
  return [...by].sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0)).map(([k, n]) => `${n} ${k}`).join(" · ");
};

/** How full a window is, for the bar's colour: under 50%, under 80%, or over. */
export const meterLevel = (util) => (util >= 0.8 ? "hot" : util >= 0.5 ? "warm" : "ok");

/**
 * A short horizontal bar filled to the window's utilization, with the number beside it.
 * A reset more than STALE_RESET_MS past (Countdown's "awaiting new reading") reads as unknown:
 * the reading describes a window that has rolled over.
 */
const Meter = ({ util, reset, now }) => {
  const t = Date.parse(reset);
  const stale = Number.isFinite(t) && Number.isFinite(now) && now - t > STALE_RESET_MS;
  const known = !stale && typeof util === "number" && Number.isFinite(util);
  const level = meterLevel(util);
  return (
    <span className="acMeter" title={reset ? `resets ${reset}` : ""}>
      <span className="acBar">
        {known && <span className={`acFill ${level}`} style={{ width: `${Math.min(100, Math.max(0, util * 100))}%` }} />}
      </span>
      <span className="acPct">{known ? fmtPct(util) : "–"}</span>
    </span>
  );
};

/** "2026-10-01T09:00:00Z" -> "2026-10-01 09:00 UTC"; the raw string when it does not parse. */
export const fmtUntil = (iso) => {
  const t = Date.parse(iso);
  return Number.isFinite(t) ? new Date(t).toISOString().slice(0, 16).replace("T", " ") + " UTC" : String(iso);
};

/** Time left in ms -> "5h:22m:12s", or "3d:04h:22m:12s" at 24 h or more; "resetting…" once it is up. */
export const fmtCountdown = (ms) => {
  if (!(ms > 0)) return "resetting…";
  const t = Math.floor(ms / 1000);
  const d = Math.floor(t / 86400), h = Math.floor(t / 3600) % 24, m = Math.floor(t / 60) % 60, sec = t % 60;
  const p = (n) => String(n).padStart(2, "0");
  return d ? `${d}d:${p(h)}h:${p(m)}m:${p(sec)}s` : `${h}h:${p(m)}m:${p(sec)}s`;
};

/** A reset further past than this means the reading is stale: no new headers since. */
export const STALE_RESET_MS = 5 * 60 * 1000;

/**
 * A countdown to an ISO reset, titled with the reset in local time; "–" when there is none.
 * A reset more than STALE_RESET_MS past reads "awaiting new reading", titled with `observedAt`.
 */
export const Countdown = ({ iso, now, observedAt }) => {
  const t = Date.parse(iso);
  if (!Number.isFinite(t)) return <span className="acCountdown acDim">–</span>;
  if (now - t > STALE_RESET_MS) {
    return <span className="acCountdown acDim" title={`last reading ${observedAt || "unknown"}`}>awaiting new reading</span>;
  }
  return <span className="acCountdown" title={new Date(t).toLocaleString()}>{fmtCountdown(t - now)}</span>;
};

/** The account's Fable limit (engine/README.md, "The usage meter"): only ever reached or nothing, when not reached or unknown. */
export const fableText = (usage, now = Date.now()) =>
  usage && usage.fableLimit === "reached"
    ? `Fable: reached, resets in ${usage.fableLimitReset && Number.isFinite(Date.parse(usage.fableLimitReset))
        ? fmtCountdown(Date.parse(usage.fableLimitReset) - now) : "unknown"}`
    : "";

/**
 * Whether the last reading carried the Fable limit's meter (`fableUtilization`,
 * `fableReset`: the `7d_oi` headers, on a claude-fable-* reply only). Without
 * one the tile draws no Fable meter at all, rather than a 0%.
 */
export const hasFableReading = (usage) =>
  !!usage && typeof usage.fableUtilization === "number" && Number.isFinite(usage.fableUtilization);

const FableRow = ({ usage, now }) => {
  const reached = usage && usage.fableLimit === "reached";
  if (!reached) return null;
  const t = Date.parse(usage.fableLimitReset);
  return (
    <div className="acFable warn"
      title={Number.isFinite(t) ? new Date(t).toLocaleString() : usage.fableLimitSince ? `since ${usage.fableLimitSince}` : ""}>
      {fableText(usage, now)}
    </div>
  );
};

/** One window's row in a tile: label, bar and percent, countdown to its reset. */
const WindowRow = ({ label, title, usage, util, reset, now }) => (
  <div className="acWindow">
    <span className="acWinLabel" title={title}>{label}</span>
    {usage ? <Meter util={util} reset={reset} now={now} /> : <span className="acMeter acDim">–</span>}
    {usage ? <Countdown iso={reset} now={now} observedAt={usage.observedAt} /> : <span className="acCountdown acDim">–</span>}
  </div>
);

/** Ticks every second from `start`, so the countdowns move without a refetch; stops on unmount. */
const useNow = (start) => {
  const [now, setNow] = useState(() => start ?? Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  return now;
};

/** Said wherever the app asks for an account's email. */
export const EMAIL_NOTE = "For your reference only: the engine never uses this address.";

/** The add form: a name, a provider, optionally an email note, and optionally a credential for supported providers. */
export const AddForm = ({ busy, run, onClose }) => {
  const [name, setName] = useState("");
  const [provider, setProvider] = useState("anthropic");
  const [email, setEmail] = useState("");
  const [token, setToken] = useState("");
  const submit = async (e) => {
    e.preventDefault();
    const n = name.trim();
    const added = await run({ op: "add", name: n, provider, email }, `added ${n}`);
    if (!added) return;
    if (provider !== "codex" && token.trim()) {
      const t = token; setToken("");
      if (!(await run({ op: "credential", name: n, provider, token: t }, `added ${n} and set its credential`))) return;
    }
    onClose();
  };
  const problem = token.trim() ? credentialProblem(provider, token) : null;
  return (
    <form className="acForm" onSubmit={submit}>
      <input className="acInput" placeholder="name, e.g. anthropic-1" value={name} autoFocus
        onChange={(e) => setName(e.target.value)} spellCheck={false} />
      <select className="acInput" value={provider} onChange={(e) => { setProvider(e.target.value); setToken(""); }}>
        {PROVIDERS.map((p) => <option key={p} value={p}>{p === "codex" ? "codex (ChatGPT sign-in)" : p}</option>)}
      </select>
      <input className="acInput" type="text" placeholder="email (optional), for your reference" value={email}
        onChange={(e) => setEmail(e.target.value)} spellCheck={false} />
      {provider !== "codex" && <input className="acInput acToken" type="password" autoComplete="off" placeholder={`credential (optional): ${TOKEN_HINT[provider]}`}
        value={token} onChange={(e) => setToken(e.target.value)} />}
      <button className="acBtn primary" type="submit" disabled={busy || !name.trim() || Boolean(problem)}>{busy && token.trim() ? "Verifying…" : "Add"}</button>
      <button className="acBtn" type="button" disabled={busy} onClick={onClose}>Cancel</button>
      <div className="acNote">{EMAIL_NOTE}</div>
      {problem && <div className="acProblem" role="alert">{problem}</div>}
    </form>
  );
};

/** Rename one account, in a row under it, prefilled with its name; the engine refuses while a run is live on it. */
export const RenameForm = ({ account, busy, run, onClose }) => {
  const [to, setTo] = useState(account.name);
  const submit = async (e) => {
    e.preventDefault();
    if (await run({ op: "rename", name: account.name, to }, `renamed ${account.name} to ${to.trim()}`)) onClose();
  };
  return (
    <div className="acEdit">
      <form className="acForm" onSubmit={submit}>
        <input className="acInput" type="text" autoFocus spellCheck={false} placeholder="new name, e.g. anthropic-2"
          value={to} onChange={(e) => setTo(e.target.value)} />
        <button className="acBtn primary" type="submit" disabled={busy}>Rename</button>
        <button className="acBtn" type="button" disabled={busy} onClick={onClose}>Cancel</button>
        <div className="acNote">Its usage meter and run history move with it.</div>
      </form>
    </div>
  );
};

/** Set, change or clear one account's email note, in a row under it; an empty submit clears it. */
export const EmailForm = ({ account, busy, run, onClose, keepOpen = false }) => {
  const [email, setEmail] = useState(account.email || "");
  const submit = async (e) => {
    e.preventDefault();
    const done = email.trim() ? `set ${account.name}'s email` : `cleared ${account.name}'s email`;
    if (await run({ op: "email", name: account.name, email }, done) && !keepOpen) onClose();
  };
  return (
    <div className="acEdit">
      <form className="acForm" onSubmit={submit}>
        <input className="acInput" type="text" autoFocus spellCheck={false} placeholder="email (optional), for your reference"
          value={email} onChange={(e) => setEmail(e.target.value)} />
        <button className="acBtn primary" type="submit" disabled={busy}>Save</button>
        <button className="acBtn" type="button" disabled={busy} onClick={onClose}>Cancel</button>
        <div className="acNote">{EMAIL_NOTE}</div>
      </form>
    </div>
  );
};

/** Set or replace one account's credential, in a row under it. */
const CredentialForm = ({ account, busy, run, onClose, keepOpen = false }) => {
  const [token, setToken] = useState("");
  const [fileName, setFileName] = useState("");
  const [fileError, setFileError] = useState("");
  const codex = account.provider === "codex";
  const submit = async (e) => {
    e.preventDefault();
    const t = token; setToken("");
    if (await run({ op: "credential", name: account.name, provider: account.provider, token: t },
      codex ? `imported ${account.name}'s Codex login state` : `set ${account.name}'s credential`) && !keepOpen) onClose();
  };
  const problem = !codex && token.trim() ? credentialProblem(account.provider, token) : null;
  return (
    <div className="acEdit">
      <form className="acForm" onSubmit={submit}>
        {codex
          ? <>
              <input className="acInput" type="file" accept="application/json,.json" autoFocus
                onChange={async (e) => {
                  const file = e.target.files && e.target.files[0];
                  setToken(""); setFileName(""); setFileError("");
                  if (!file) return;
                  if (file.size > 64 * 1024) { setFileError("auth.json is over 65536 bytes"); return; }
                  try { setToken(await file.text()); setFileName(file.name); }
                  catch { setFileError("could not read the selected auth.json"); }
                }} />
              {fileName && <div className="acNote">Selected {fileName}; its contents are sent to the engine for encrypted storage and are never displayed.</div>}
              <div className="acNote">Choose the Codex CLI auth.json from the signed-in CODEX_HOME. Smith execution is not connected yet.</div>
              {fileError && <div className="acProblem" role="alert">{fileError}</div>}
            </>
          : <input className="acInput acToken" type="password" autoComplete="off" autoFocus
              placeholder={`${account.hasCredential ? "replace" : "set"} ${account.name}'s credential: ${TOKEN_HINT[account.provider] || ""}`}
              value={token} onChange={(e) => setToken(e.target.value)} />}
        <button className="acBtn primary" type="submit" disabled={busy || !(codex ? token : token.trim()) || Boolean(problem)}>{busy ? "Saving…" : codex ? "Import auth.json" : "Save"}</button>
        <button className="acBtn" type="button" disabled={busy} onClick={onClose}>Cancel</button>
        {problem && <div className="acProblem" role="alert">{problem}</div>}
      </form>
    </div>
  );
};

/** Removing is the one action that loses something (the sealed credential): say so and ask. */
const RemoveConfirm = ({ account, busy, run, onClose }) => (
  <div className="acEdit">
    <div className="acForm">
      <span className="acConfirm">Remove <b>{account.name}</b>?{account.hasCredential ? " Its credential is deleted with it." : ""}</span>
      <button className="acBtn danger" disabled={busy}
        onClick={async () => { if (await run({ op: "remove", name: account.name }, `removed ${account.name}`)) onClose(); }}>Remove</button>
      <button className="acBtn" disabled={busy} onClick={onClose}>Cancel</button>
    </div>
  </div>
);

// ---- Edit… (#1955): one modal per account, a tab per group of actions ------------

export const ACCOUNT_TABS = [
  { key: "details", label: "Details" },
  { key: "credential", label: "Credential" },
  { key: "remove", label: "Remove" },
];

/**
 * The account's Edit… modal. `run(action, done)` sends one action and answers
 * true on success (the parent then refreshes and says `done`); a refusal is the
 * open tab's `error`. `verdict` is the last Verify's { ok, text }. Rename and
 * Remove close it; every other action keeps it open on its tab.
 */
export const EditAccountDialog = ({ account: a, tab, onTab, busy, run, verify, error, verdict, onClose }) => {
  const codex = a.provider === "codex";
  return (
    <ModalOverlay label={`Edit: ${a.name} (${a.provider})`} onEscape={onClose} onBackdrop={onClose} className="erPanel">
      <div className="uoTitle">Edit: {a.name} <span className="acProvider">{a.provider}</span></div>
      <div className="erTabs" role="tablist">
        {ACCOUNT_TABS.map((t) => (
          <button key={t.key} type="button" role="tab" aria-selected={t.key === tab} className={"erTab" + (t.key === tab ? " on" : "")}
            onClick={() => onTab(t.key)}>{t.label}</button>
        ))}
      </div>
      <div className="erPage acPage" role="tabpanel">
        {tab === "details" && <>
          <div className="acForm">
            <Switch label="Enabled" checked={a.enabled} disabled={busy}
              onChange={(x) => run({ op: "enabled", name: a.name, enabled: x }, `${x ? "enabled" : "disabled"} ${a.name}`)} />
            <span className="acConfirm">{a.enabled ? "Enabled: Smith runs may bill to it." : "Disabled: the picker skips it."}</span>
          </div>
          <div className="erTitle">Name</div>
          <RenameForm account={a} busy={busy} run={run} onClose={onClose} />
          <div className="erTitle">Email</div>
          <EmailForm account={a} busy={busy} run={run} onClose={onClose} keepOpen />
        </>}
        {tab === "credential" && <>
          <div className="erTitle">{codex ? (a.hasCredential ? "Replace auth.json" : "Import auth.json") : a.hasCredential ? "Replace credential" : "Set credential"}</div>
          <p className="ecProse">The credential is sealed by the engine and never shown again; what is typed here is sent once and cleared.</p>
          <CredentialForm account={a} busy={busy} run={run} onClose={onClose} keepOpen />
          {a.hasCredential && !codex && <>
            <div className="erTitle">Verify</div>
            <div className="acForm">
              <button className="acBtn" type="button" disabled={busy} title="ask the provider whether the stored credential still works"
                onClick={() => verify(a.name)}>Verify</button>
              {verdict && <span className={verdict.ok ? "acVerdict ok" : "acVerdict bad"}>{verdict.text}</span>}
            </div>
          </>}
        </>}
        {tab === "remove" && <RemoveConfirm account={a} busy={busy} run={run} onClose={onClose} />}
        {error && <div className="uoFail">{String(error)}</div>}
      </div>
    </ModalOverlay>
  );
};

/** Holds the modal's tab, each tab's refusal and the Verify verdict; `send` is the view's sender. */
export const EditAccountModal = ({ account, busy, send, verifySend, onClose, initialTab = "details" }) => {
  const [tab, setTab] = useState(initialTab);
  const [errors, setErrors] = useState({});
  const [verdict, setVerdict] = useState(null);
  const run = async (action, done) => {
    const at = tab;
    setErrors((m) => ({ ...m, [at]: null }));
    const err = await send(action, done);
    if (err) { setErrors((m) => ({ ...m, [at]: err })); return false; }
    return true;
  };
  const verify = async (name) => { setVerdict(null); setVerdict(await verifySend(name)); };
  return <EditAccountDialog account={account} tab={tab} onTab={setTab} busy={busy} run={run} verify={verify}
    error={errors[tab] || null} verdict={verdict} onClose={onClose} />;
};

/** "5 · opus medium"-style label for one tier: the tier, then its model and effort. */
export const tierLabel = (tier, model, effort) => `${tier}${model ? ` · ${model}` : ""}${effort ? ` ${effort}` : ""}`;

/**
 * `result` is what main's `engine:accounts` returned: src/engine-data.js
 * fetchAccounts' `{ accounts, usageError }` or `{ error }`, drawn as the
 * shared .err block. Without `onAction` the view draws no account controls
 * (Analytics > AI Accounts); `showUsage={false}` drops the window rows
 * (Config > AI Accounts, #640). `onRefreshUsage`, the same callback shape as
 * `onAction`, puts Refresh usage on each anthropic tile: Analytics' one write.
 */
/**
 * The FAQ: how AiAccounts.pick chooses an account, restating engine/README.md
 * "AI accounts" ("The picker", "The Fable limit"). The README wins where they differ.
 */
export const PICKER_FAQ = [
  ["Which accounts can a run bill to?",
    "The accounts of the model's provider (claude-* bills to anthropic, gpt-* to openai) that are enabled and have a credential."],
  ["Which accounts are skipped, and until when?",
    "An account whose 5-hour or 7-day window status is rejected, until that window's reset; one where a run's result recorded a session or spend limit, until that limit's reset; and one with enabled = false, whatever its resets say. Once the reset passes the account is picked again without anyone re-enabling it."],
  ["In what order are the usable accounts tried?",
    "The lowest 5-hour utilization first (no reading, or a reading whose 5-hour reset has passed, counts as 0), then the one picked least recently (never picked first), then by name, so a provider's accounts rotate."],
  ["What happens near a weekly reset?",
    "Accounts whose 7-day budget is expiring go first, most behind first: expiring is needing at least twice an even week's pace to use up what is left before the 7-day reset, (1 − 7-day utilization) × 168h / hours to the reset ≥ 2. So budget that would otherwise go unused at the reset is spent before budget that would not. Except for a model that is not Fable: an expiring account that still has Fable headroom goes after every other unsaturated account, so its 5-hour window stays free for Fable runs; it is still picked when no other account is usable, and a Fable model's order is unchanged. The rest, and equally expiring ones, follow the 5-hour order; an account with no 7-day reading, or a 7-day reset that has passed, counts as not expiring. The 5-hour window has no such rule: its budget returns every 5 hours."],
  ["What is a pinned account?",
    "A caller can pin one by setting the account process variable. A pinned account is not replaced: if it is rejected the run fails with \"account <x> is pinned and rejected until <reset>\", and a Fable run whose pinned account is at its Fable limit fails with \"account <x> is pinned and at its Fable limit until <reset>\"."],
  ["What is the Fable gate?",
    "For a claude-fable-* model the picker also skips an account whose Fable limit is reached with its reset still ahead; when no anthropic account is left, start smith fails as an incident with \"no account can run Fable until <earliest reset>\". Every other model ignores the Fable limit."],
  ["What if nothing is usable?",
    "When nothing is usable and at least one account was skipped as rejected, a real run fails at start smith as an incident with \"no account available until <earliest reset>\"; retry the job after that reset. With no usable account at all (none, all disabled, no credentials) it stays an incident until one is usable and the job is retried."],
];

export const FAQ_TITLE = "How the picker chooses an account";

/** The FAQ dialog; drawn in place (a plain function, not a component). */
export const faqPanel = (onClose) => (
  <div className="acFaq" role="dialog" aria-label={FAQ_TITLE}>
    <div className="acFaqBar">
      <h2>{FAQ_TITLE}</h2>
      <button className="acFaqClose" title="Close (Esc)" onClick={onClose}>Close ✕</button>
    </div>
    {PICKER_FAQ.map(([q, a]) => (
      <div className="acFaqEntry" key={q}><p><b>{q}</b></p><p>{a}</p></div>
    ))}
  </div>
);

export const TIER_ACCOUNTS_TITLE = "Which account would be used?";

// Folds behind its heading like Doctor (flow.jsx, DOCTOR_KEY),
// open by default (unlike those) and remembered per viewer under its own key.
const TIER_ACCOUNTS_OPEN_KEY = "accounts.tierAccountsOpen";

/** A local time for a `blockedUntil`; the raw string when it does not parse. */
export const fmtLocal = (iso) => {
  const t = Date.parse(iso);
  return Number.isFinite(t) ? new Date(t).toLocaleString() : String(iso);
};

/**
 * Analytics > AI Accounts' lookup panel (#1260): each tier and the account a
 * Smith run at it would get right now, `GET /tiers/accounts` (the engine's
 * peek, never a pick), or `none` and until when. Asked once when it mounts,
 * which is when the view opens, and again on its own Refresh; it listens to
 * no engine event. Not an account: no controls, no meters.
 */
export const TierAccountsTile = ({ fetchRows = () => window.fleet.tierAccounts() }) => {
  const [result, setResult] = useState(null); // { rows, error } | null while the first answer is out
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(() => {
    try { return localStorage.getItem(TIER_ACCOUNTS_OPEN_KEY) !== "false"; } catch (e) { return true; }
  });
  const flipOpen = () => {
    setOpen(!open);
    try { localStorage.setItem(TIER_ACCOUNTS_OPEN_KEY, String(!open)); } catch (e) { /* per-viewer nicety only */ }
  };
  const load = async () => {
    setLoading(true);
    let r;
    try { r = await fetchRows(); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}`, rows: [] }; }
    setResult(r || { error: "no response from the main process", rows: [] });
    setLoading(false);
  };
  useEffect(() => { load(); }, []);
  const rows = (result && Array.isArray(result.rows)) ? result.rows : [];
  return (
    <section className="tier-accounts-tile" aria-label={TIER_ACCOUNTS_TITLE}>
      <header className="taHead">
        <h2 className="tdFold taFold" role="button" tabIndex={0} aria-expanded={open}
          title={open ? "Hide the table" : "Show the table"} onClick={flipOpen}
          onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); flipOpen(); } }}>
          <span className="tdFoldArrow">{open ? "▾" : "▸"}</span>
          {TIER_ACCOUNTS_TITLE}
        </h2>
        <button className={"refreshBtn" + (loading ? " spinning" : "")} title="Ask the engine again"
          disabled={loading} onClick={() => load()}>⟳</button>
      </header>
      {open && (!result ? <div className="nmEmpty">asking the engine…</div>
        : result.error ? <div className="err">{String(result.error)}</div>
        : <table className="taTable">
            <thead><tr><th>Tier</th><th>Model</th><th>Effort</th><th>Account</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.tier}>
                  <td>{r.tier}</td>
                  <td>{shortModel(r.model)}</td>
                  <td>{r.effort}</td>
                  <td>{r.account
                    ? <span className="taAccount">{r.account}</span>
                    : <span className="taNone">none{r.blockedUntil ? <span title={r.blockedUntil}> until {fmtLocal(r.blockedUntil)}</span> : null}</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>)}
    </section>
  );
};

export const Accounts = ({ result, onRefresh, refreshing, onAction, onRefreshUsage, showUsage = true, now: nowProp, fetchTierAccounts }) => {
  const now = useNow(nowProp);
  const { accounts = [], usageError = null, error } = result || {};
  // { kind: "add" } | { kind: "edit", name } | null
  const [editing, setEditing] = useState(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null); // { ok, text }
  const [faq, setFaq] = useState(false);
  useEffect(() => {
    if (!faq) return undefined;
    const onKey = (e) => { if (e.key === "Escape") setFaq(false); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [faq]);
  const close = () => setEditing(null);
  // Send one action; say how it went; refresh the list after any success.
  const run = async (action, done, send = onAction) => {
    setBusy(true); setNotice(null);
    let r;
    try { r = await send(action); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setBusy(false);
    if (!r || r.error) { setNotice({ ok: false, text: (r && r.error) || "no answer from the main process" }); return false; }
    setNotice({ ok: true, text: done });
    if (onRefresh) onRefresh();
    return true;
  };
  // The Edit… modal's sender: like run, but a refusal is answered (for the open tab), not made the notice.
  const send = async (action, done) => {
    setBusy(true); setNotice(null);
    let r;
    try { r = await onAction(action); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setBusy(false);
    if (!r || r.error) return (r && r.error) || "no answer from the main process";
    setNotice({ ok: true, text: done });
    if (onRefresh) onRefresh();
    return null;
  };
  // Verify changes nothing: the verdict, valid or not, is answered to the credential tab, and nothing is refreshed.
  const verify = async (name) => {
    setBusy(true);
    let r;
    try { r = await onAction({ op: "verify", name }); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setBusy(false);
    if (!r || r.error || !r.verdict) return { ok: false, text: (r && r.error) || "no answer from the main process" };
    return { ok: r.verdict.valid, text: `${name}: ${r.verdict.detail || (r.verdict.valid ? "accepted" : "refused")}` };
  };
  const head = (
    <div className="head">
      <h1>AI Accounts{accounts.length ? ` · ${accounts.length}` : ""}</h1>
      <span className="meta">
        <button className="acFaqBtn" aria-expanded={faq} onClick={() => setFaq((f) => !f)}>FAQ</button>
        {onAction && !error &&
          <button className="acBtn" disabled={busy || (editing && editing.kind === "add")}
            onClick={() => { setNotice(null); setEditing({ kind: "add" }); }}>+ Add account</button>}
        <button className={"refreshBtn" + (refreshing ? " spinning" : "")} title="Refresh now"
          disabled={refreshing} onClick={() => onRefresh && onRefresh()}>⟳</button>
      </span>
    </div>
  );
  const faqView = faq && faqPanel(() => setFaq(false));
  if (error) return <div className="acView">{head}{faqView}{notice && <div className={notice.ok ? "acNotice" : "err"}>{notice.text}</div>}<div className="err">{String(error)}</div></div>;
  const edited = editing && editing.kind === "edit" && accounts.find((a) => a.name === editing.name);
  return (
    <div className="acView">
      {head}
      {faqView}
      {notice && <div className={notice.ok ? "acNotice" : "err"}>{notice.text}</div>}
      {editing && editing.kind === "add" && <AddForm busy={busy} run={run} onClose={close} />}
      {edited && onAction && <EditAccountModal key={edited.name} account={edited} busy={busy} send={send} verifySend={verify} onClose={close} />}
      {showUsage && <TierAccountsTile fetchRows={fetchTierAccounts} />}
      {usageError && <div className="nmWarn">usage meter unavailable: {usageError}</div>}
      {!accounts.length
        ? <div className="nmEmpty">{result && result.loading ? "asking the engine…"
            : onAction ? "The engine has no AI accounts yet. Add one above." : "The engine has no AI accounts yet."}</div>
        : <div className="acGrid">
            {accounts.map((a) => {
              const st = accountState(a);
              const u = a.usage;
              const running = runningText(a.running);
              return (
                <article key={a.name} className={"acTile ac-" + st.key}>
                  <header className="acTileHead">
                    <span className="acName">{a.name}</span>
                    <span className="acProvider">{a.provider}</span>
                    <span className="acState">{showUsage && st.key === "rejected" ? "" : st.text}</span>
                    {running && <span className="acRunning" title={`running now: ${a.running.map((r) => `${r.count} ${r.model}`).join(", ")}`}><span className="acRunningLabel">running</span> {running}</span>}
                  </header>
                  {a.email && <div className="acEmail" title="for your reference only: the engine never uses it">{a.email}</div>}
                  {!showUsage ? null
                    : NO_METER.has(a.provider)
                    ? <div className="acTileBody">
                        <div className="acWindow"><span className="acWinLabel acWinLabelWide">{a.provider === "codex" ? "status" : "balance"}</span><span className="acDim">{a.provider === "codex" ? (a.hasCredential ? "login stored; Smith runs not connected" : "ChatGPT sign-in not connected") : "no usage data"}</span></div>
                      </div>
                    : <div className="acTileBody">
                        <WindowRow label="5h" usage={u} util={u && u.fiveHourUtilization} reset={u && u.fiveHourReset} now={now} />
                        <WindowRow label="7d" usage={u} util={u && u.sevenDayUtilization} reset={u && u.sevenDayReset} now={now} />
                        {hasFableReading(u) &&
                          <WindowRow label="F" title="Fable limit" usage={u} util={u.fableUtilization} reset={u.fableReset} now={now} />}
                        <FableRow usage={u} now={now} />
                      </div>}
                  <footer className="acTileFoot">
                    <div className="acFacts">
                      <span className="acAge" title={u ? u.observedAt : ""}>{u ? `seen ${fmtAgo(u.observedAt, now)}` : "never used"}</span>
                      {!NO_METER.has(a.provider) && <span>{u && typeof u.requests === "number" ? `${u.requests} requests` : "– requests"}</span>}
                      <span title={a.credentialSetAt ? `set ${a.credentialSetAt}` : ""}>{a.hasCredential ? a.provider === "codex" ? "Codex login stored" : "credential set" : a.provider === "codex" ? "sign-in not connected" : "no credential"}</span>
                    </div>
                    {onAction &&
                      <div className="acActions">
                        <button className="acBtn" disabled={busy}
                          onClick={() => { setNotice(null); setEditing({ kind: "edit", name: a.name }); }}>Edit…</button>
                      </div>}
                    {onRefreshUsage && a.provider === "anthropic" &&
                      <div className="acActions">
                        <button className="acBtn" disabled={busy || !a.hasCredential}
                          title={a.hasCredential ? "ask Anthropic for this account's usage meter now (one tiny Haiku request)" : "set a credential to refresh the meter"}
                          onClick={() => run({ op: "refreshUsage", name: a.name }, `${a.name}: usage refreshed`, onRefreshUsage)}>Refresh usage</button>
                      </div>}
                  </footer>
                </article>
              );
            })}
          </div>}
    </div>
  );
};
