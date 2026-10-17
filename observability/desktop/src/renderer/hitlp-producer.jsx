// hitlp-producer.jsx — Explorer > Utils > HITLP producer (#1947): a test
// client for the human-in-the-loop protocol (codefrak-oss/hitlp,
// spec/hitlp.md, 0.2 since #2489). Pick a server, paste its requester token
// (held in component state only: never stored, never shown), Ask or Approve,
// fill the form (every field prefilled, src/hitlp-request.js), Submit: main
// (`hitlp:call`, src/hitlp-client.js) sends the MCP `tools/call` with that
// token as the bearer and the page shows the exact request and the raw
// answer. A Task handle in the answer offers Poll, one `tasks/get`; nothing
// polls by itself.

import { useEffect, useRef, useState } from "react";
import { ANSWER_KINDS, DEFAULT_ON_TIMEOUT, PRIORITIES, formFields, defaultForm, payloadDigest,
  toolArguments, taskIdOf, shownBody } from "../hitlp-request.js";

/** Where the last Server URL is kept, per viewer, across launches. */
export const SERVER_KEY = "hitlp-producer.server";
/** The Server datalist: the repo's http and sse MCP servers, no repeats (the engine serves no HITLP since #2484; nothing suggests its URL). */
export const serverSuggestions = (servers) => [...new Set([
  ...(Array.isArray(servers) ? servers : []).filter((s) => s && (s.kind === "http" || s.kind === "sse") && s.url).map((s) => s.url),
].filter(Boolean))];

const LABELS = {
  idempotencyKey: "Idempotency key", deadline: "Deadline", defaultOnTimeout: "Default on timeout", priority: "Priority",
  requires: "Requires", context: "Context", requiresHuman: "Requires a human", "asserted.why": "Why (asserted)",
  "asserted.onBehalfOf": "On behalf of (asserted)", "asserted.recommendation": "Recommendation (asserted)",
  "asserted.lineage": "Lineage (asserted)", "asserted.asOf": "As of (asserted)",
  question: "Question", answerKind: "Answer kind", options: "Options", responseSchema: "Response schema",
  action: "Action", payload: "Payload", payloadDigest: "Payload digest", scope: "Scope",
};
const CHOICES = { defaultOnTimeout: DEFAULT_ON_TIMEOUT, priority: PRIORITIES };

const Options = ({ options, onChange }) => {
  const list = Array.isArray(options) ? options : [];
  const set = (i, k, v) => onChange(list.map((o, j) => (j === i ? { ...o, [k]: v } : o)));
  return (
    <div className="hpOptions">
      {list.map((o, i) => (
        <div key={i} className="hpOption">
          <input className="acInput" placeholder="value" value={o.value} onChange={(e) => set(i, "value", e.target.value)} />
          <input className="acInput" placeholder="label" value={o.label} onChange={(e) => set(i, "label", e.target.value)} />
          <button className="acBtn" title="Remove this option" onClick={() => onChange(list.filter((_, j) => j !== i))}>×</button>
        </div>
      ))}
      <button className="acBtn" onClick={() => onChange([...list, { value: "", label: "" }])}>Add option</button>
    </div>
  );
};

const Field = ({ field, form, onField }) => {
  const v = form[field.name];
  const set = (val) => onField(field.name, val);
  let input;
  if (field.name === "answerKind") {
    input = <select className="acInput" value={v} onChange={(e) => set(e.target.value)}>{ANSWER_KINDS.map((k) => <option key={k.id} value={k.id}>{k.label}</option>)}</select>;
  } else if (field.type === "select") {
    input = <select className="acInput" value={v} onChange={(e) => set(e.target.value)}>
      {!field.required && <option value="">(none)</option>}
      {(CHOICES[field.name] || []).map((c) => <option key={c} value={c}>{c}</option>)}
    </select>;
  } else if (field.type === "json") {
    input = <textarea className="hpJson" rows={4} spellCheck={false} value={v ?? ""} onChange={(e) => set(e.target.value)} />;
  } else if (field.type === "list") {
    input = <Options options={v} onChange={set} />;
  } else if (field.type === "boolean") {
    input = <input type="checkbox" className="hpCheck" checked={v === true} onChange={(e) => set(e.target.checked)} />;
  } else {
    input = <input className="acInput" spellCheck={false} value={v ?? ""} placeholder={field.type === "datetime" ? "2026-10-09T12:00:00Z" : ""}
                   onChange={(e) => set(e.target.value)} />;
  }
  return (
    <label className="hpField" data-field={field.name}>
      <span className="hpLabel">{LABELS[field.name] || field.name}{field.required ? " *" : ""}</span>
      {input}
    </label>
  );
};

const Pane = ({ title, text, status, onCopy }) => (
  <section className="hpPane" data-pane={title}>
    <h2>{title}{status != null ? <span className="hpStatus"> HTTP {status}</span> : null}
      <button className="acBtn" title={`Copy the ${title.toLowerCase()}`} onClick={() => onCopy && onCopy(text)}>Copy</button></h2>
    <pre className="hpRaw">{text}</pre>
  </section>
);

/**
 * The page, hookless: `form` (hitlp-request.js's defaultForm shape), `server`
 * and `suggestions`, `token` (the requester token, drawn as a password
 * field), `result` (main's `hitlp:call` answer, null before any), `formError`
 * (a JSON area that does not parse), `busy`.
 */
export const HitlpProducerView = ({ server, suggestions = [], token = "", form, result, formError, busy,
  onServer, onToken, onKind, onField, onReset, onSubmit, onPoll, onCopy }) => {
  const fields = formFields(form.kind, form.answerKind);
  const taskId = result && !result.error ? taskIdOf(result.body) : null;
  const responseText = !result ? null : result.error ? `transport error: ${result.error}` : shownBody(result.body);
  return (
    <div className="fleet-root hitlpProducer">
      <h1>HITLP producer</h1>
      <div className="hpForm hpHead">
      <label className="hpField" data-field="server">
        <span className="hpLabel">Server *</span>
        <input className="acInput" list="hp-servers" spellCheck={false} value={server} onChange={(e) => onServer(e.target.value)} />
        <datalist id="hp-servers">{suggestions.map((u) => <option key={u} value={u} />)}</datalist>
      </label>
      <label className="hpField" data-field="token">
        <span className="hpLabel">Requester token</span>
        <input className="acInput" type="password" autoComplete="off" spellCheck={false} value={token} placeholder="the HITLP server's requester token, sent as its bearer"
               onChange={(e) => onToken && onToken(e.target.value)} />
      </label>
      <label className="hpField" data-field="kind">
        <span className="hpLabel">Kind *</span>
        <select className="acInput" value={form.kind} onChange={(e) => onKind(e.target.value)}>
          <option value="ask">Ask (human.ask)</option>
          <option value="approve">Approve (human.approve)</option>
        </select>
      </label>
      </div>
      <div className="hpForm">{fields.map((f) => <Field key={f.name} field={f} form={form} onField={onField} />)}</div>
      <div className="hpButtons">
        <button className="acBtn primary" disabled={busy || !server} onClick={onSubmit}>Submit</button>
        <button className="acBtn" disabled={busy} onClick={onReset}>Reset</button>
        {taskId && <button className="acBtn" disabled={busy} title={`tasks/get ${taskId}`} onClick={() => onPoll(taskId)}>Poll</button>}
        {busy && <span className="muted">sending…</span>}
      </div>
      {formError && <div className="err">{formError}</div>}
      {result && (
        <div className="hpPanes">
          <Pane title="Request" text={result.request ? JSON.stringify(result.request, null, 2) : "(nothing sent)"} onCopy={onCopy} />
          <Pane title="Response" text={responseText} status={result.error ? null : result.status} onCopy={onCopy} />
        </div>
      )}
    </div>
  );
};

const readServer = () => {
  try { const s = localStorage.getItem(SERVER_KEY); if (s) return s; } catch (e) { /* per-viewer nicety only */ }
  return "";
};

export const HitlpProducer = ({ repo }) => {
  const [server, setServer] = useState(readServer);
  // The requester token: this component's state only, never localStorage, never a pane or a log line.
  const [token, setToken] = useState("");
  const [servers, setServers] = useState([]);
  const [login, setLogin] = useState(null);
  const [form, setForm] = useState(() => defaultForm("ask"));
  const [result, setResult] = useState(null);
  const [formError, setFormError] = useState(null);
  const [busy, setBusy] = useState(false);
  const seq = useRef(0);
  // The login arrives after mount: it is the default `asserted.onBehalfOf`.
  useEffect(() => {
    let live = true;
    window.fleet.login().then((l) => {
      if (!live) return;
      setLogin(l || null);
      setForm((f) => (f["asserted.onBehalfOf"] ? f : { ...f, "asserted.onBehalfOf": l || "" }));
    }).catch(() => {});
    return () => { live = false; };
  }, []);
  useEffect(() => {
    let live = true;
    if (!repo) { setServers([]); return undefined; }
    window.fleet.mcpServers(repo).then((r) => { if (live) setServers((r && r.servers) || []); }).catch(() => {});
    return () => { live = false; };
  }, [repo]);
  const onServer = (url) => {
    setServer(url);
    try { localStorage.setItem(SERVER_KEY, url); } catch (e) { /* per-viewer nicety only */ }
  };
  const onKind = (kind) => setForm(defaultForm(kind, { login }));
  const onReset = () => { setForm(defaultForm(form.kind, { login })); setFormError(null); };
  const onField = (name, value) => setForm((f) => {
    const next = { ...f, [name]: value };
    // The digest follows the payload; a payload that does not parse leaves it as it was.
    if (name === "payload") { const d = payloadDigest(value); if (d) next.payloadDigest = d; }
    return next;
  });
  const send = async (op) => {
    const n = ++seq.current;
    setBusy(true);
    try {
      const r = await window.fleet.hitlpCall(server, op, token);
      if (n === seq.current) setResult(r || { request: null, error: "no answer from main" });
    } catch (e) {
      if (n === seq.current) setResult({ request: null, error: String(e && e.message ? e.message : e) });
    } finally {
      if (n === seq.current) setBusy(false);
    }
  };
  const onSubmit = () => {
    const built = toolArguments(form);
    if (built.error) { setFormError(built.error); return; }
    setFormError(null);
    send({ tool: built.tool, arguments: built.arguments });
  };
  const onPoll = (taskId) => send({ taskId });
  const onCopy = (text) => { try { navigator.clipboard.writeText(text); } catch (e) { /* nothing to copy into */ } };
  return <HitlpProducerView server={server} suggestions={serverSuggestions(servers)} token={token} form={form} result={result}
                            formError={formError} busy={busy} onServer={onServer} onToken={setToken} onKind={onKind} onField={onField}
                            onReset={onReset} onSubmit={onSubmit} onPoll={onPoll} onCopy={onCopy} />;
};
