// sign-in.jsx — Config > frakworks > Sign in to the engine (#2206):
// a token the engine minted
// (`POST /tokens`) pasted once, sent inward over `window.fleet.signIn` and kept
// by main, encrypted with Electron's safeStorage (main's token keeper). This
// component never holds a token prop: `result` is main's `{ signedIn, login,
// name, error }`, the login and the token's name are all it draws, and the
// field is cleared after every submit, success or not, so no token stays in
// the DOM. Shape shared with smith-hosting.jsx: acSetting > acSettingHead,
// fmmHint, err/notice lines, then the content.

import { useState } from "react";


/**
 * `result` is `{ signedIn, login, name, error }` (or `{ loading: true }` before
 * main's first answer); `onSignIn(token)` resolves to `{ login, name }` or
 * `{ error }`; `onSignOut()` to `{ ok }` or `{ error }`; `onRefresh()` re-reads
 * the status (after either). `busy` disables the controls meanwhile. `web`
 * is the web version (src/web/), where the page itself keeps the token.
 * `title`, `hint`, `action` and `signedInText` relabel it for the judge teach
 * token (#2436), a first-mate token kept the same way. `urlField` (the
 * HITLP server, #2487) adds a URL box: `onSignIn(token, url)`, and `result`
 * and the answer then carry `url`, drawn in place of the login.
 */
export const SignInPanel = ({ result, onSignIn, onSignOut, onRefresh, web = false, title = "Sign in to the engine", hint = null,
  action = "Sign in", signedInText = "Signed in as", urlField = null, placeholder = "frk_…" }) => {
  const [text, setText] = useState("");
  const [url, setUrl] = useState("");
  const [busy, setBusy] = useState(false);
  const [keep, setKeep] = useState(false);
  const [notice, setNotice] = useState(null);
  const r = result || {};
  const run = async (what) => {
    setBusy(true); setNotice(null);
    let out;
    try { out = await what(); }
    catch (e) { out = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setBusy(false);
    setText("");
    if (!out) setNotice({ ok: false, text: "no response from the main process" });
    else if (out.error) setNotice({ ok: false, text: out.error });
    else if (urlField && out.url) setNotice({ ok: true, text: `${signedInText} ${out.url}` });
    else if (out.login) setNotice({ ok: true, text: `${signedInText} @${out.login} with token ${out.name || ""}`.trim() });
    else setNotice({ ok: true, text: action === "Sign in" ? "Signed out" : "Cleared" });
    if (onRefresh) onRefresh();
  };
  const submit = (e) => {
    if (e) e.preventDefault();
    if (busy || !onSignIn || !text.trim()) return;
    if (urlField && !url.trim()) return;
    run(() => (urlField ? onSignIn(text, url.trim()) : web ? onSignIn(text, { keep }) : onSignIn(text)));
  };
  return (
    <section className="acSetting signIn">
      <div className="acSettingHead">
        <span className="acName">{title}</span>
      </div>
      <p className="fmmHint">{hint ? hint : web
        ? "A bearer token the engine minted (POST /tokens, or First mate's mint_token). The web version keeps it in this tab's session storage until you sign out or close the tab, or on this device until you sign out with Keep me signed in (a home-screen app, README \"iPad\")."
        : "A bearer token the engine minted (POST /tokens, or First mate's mint_token). Kept encrypted with safeStorage; the window never sees it. First mate uses it from its next start."}</p>
      {r.error && <div className="err">{r.signedIn ? `The engine refused the token: ${r.error}. The app keeps working with the login header.` : r.error}</div>}
      {notice && <div className={notice.ok ? "acNotice" : "err"}>{notice.text}</div>}
      {r.loading
        ? <div className="fmmEmpty">asking main…</div>
        : r.signedIn
        ? (
          <div className="signInRow">
            {urlField
              ? <span className="signInWho">{signedInText} <b>{r.url || "?"}</b></span>
              : <span className="signInWho">{signedInText} <b>@{r.login || "?"}</b>{r.name ? <> with token <b>{r.name}</b></> : null}</span>}
            <button type="button" className="acBtn" disabled={busy || !onSignOut} onClick={() => run(() => onSignOut())}>{action === "Sign in" ? "Sign out" : "Clear"}</button>
          </div>
        )
        : (
          <form className="signInRow" onSubmit={submit}>
            {urlField && <input className="acInput signInUrl" type="url" autoComplete="off" spellCheck="false" aria-label={urlField}
              placeholder={urlField} value={url} disabled={busy} onChange={(e) => setUrl(e.target.value)} />}
            <input className="acInput signInToken" type="password" autoComplete="off" spellCheck="false" aria-label={urlField ? "credential" : "engine token"}
              placeholder={placeholder} value={text} disabled={busy} onChange={(e) => setText(e.target.value)} />
            <button type="submit" className="acBtn" disabled={busy || !text.trim() || (!!urlField && !url.trim())}>{action}</button>
            {web && !urlField && (
              <label className="signInKeep">
                <input type="checkbox" checked={keep} disabled={busy} onChange={(e) => setKeep(e.target.checked)} /> Keep me signed in on this device
              </label>
            )}
          </form>
        )}
    </section>
  );
};
