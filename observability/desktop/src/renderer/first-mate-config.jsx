// first-mate-config.jsx — Config > Repos' Configure First Mate modal (#1801):
// what a First mate for one repo gets, in the Edit… modal's style
// (ModalOverlay, a tab row, a page per tab with prose and its own Save). The
// one page so far, MCP servers, lists the servers the engine holds for the
// repo (engine/README.md, "First mate's MCP servers"), adds, edits, removes
// and tests each; every save is followed by a test, and each row shows its
// last result. Each server says who gets it (#2449): the First mate on this
// Mac, Smith runs of the repo on the cluster, or both; the desktop's own
// frakworks-mcp.json still holds only what the engine's /config answers, the
// First mate's. A secret value goes in and is never shown again: the engine
// answers only whether it is set and since when. The IPC is `window.fleet`'s
// mcpServers, saveMcpServer, deleteMcpServer and testMcpServer, passed in as
// `api` so a test can stand one in.

import { useEffect, useRef, useState } from "react";
import { ModalOverlay } from "./modal-overlay.jsx";

/** The modal's tabs: further pages join this list. */
export const CONFIG_TABS = [
  { key: "mcp", label: "MCP servers" },
];

export const MCP_KINDS = ["http", "sse", "stdio"];

/** Who gets a server (engine.repo_mcp_server.audience): the wire value and its label in the form and the list. */
export const MCP_AUDIENCES = [
  { value: "first-mate", label: "First mate on this Mac" },
  { value: "smith", label: "Smith runs on the cluster" },
  { value: "both", label: "Both" },
];
export const audienceLabel = (value) => (MCP_AUDIENCES.find((a) => a.value === value) || { label: value }).label;

/**
 * A server's role (engine.repo_mcp_server.role, #100008): none, or `ops`, a server that answers whether a commit is in
 * production for the repo (the `ops/1` contract: a tool `is_deployed(sha)` answering `{state: deployed|not_yet}`). The
 * engine probes an ops server on save and refuses one that does not conform.
 */
export const MCP_ROLES = [
  { value: "", label: "None" },
  { value: "ops", label: "Ops: answers whether a commit is deployed (ops/1)" },
];
export const roleLabel = (value) => (value === "ops" ? "ops" : "");

/** A blank form for a new server; the audience select starts at the First mate, and the PUT body always carries the choice. */
export const emptyForm = () => ({ isNew: true, name: "", kind: "http", audience: "first-mate", role: "", url: "", command: "", args: "", values: [] });

/** The form for a server as the engine reads it: secret values blank, marked set. */
export const formOf = (server) => {
  const stdio = server.kind === "stdio";
  const values = Object.entries((stdio ? server.env : server.headers) || {}).map(([key, e]) => (e && e.secret
    ? { key, value: "", secret: true, set: !!e.set, since: e.since || null }
    : { key, value: (e && e.value) || "", secret: false, set: false, since: null }));
  return { isNew: false, name: server.name, kind: server.kind, audience: server.audience || "first-mate", role: server.role || "", url: server.url || "",
    command: server.command || "", args: (server.args || []).join("\n"), values };
};

/**
 * The PUT body for a form: `kind`, `audience` (who gets it), `role` only when one is chosen (`ops`), then `url` and
 * `headers` for http/sse, `command`, `args` (one per line) and `env` for stdio. A secret left blank that the engine has
 * set is sent as `{secret: true}`, which keeps it.
 */
export const serverBody = (form) => {
  const values = {};
  for (const v of form.values || []) {
    const key = String(v.key || "").trim();
    if (!key) continue;
    if (v.secret) values[key] = v.value ? { value: v.value, secret: true } : { secret: true };
    else values[key] = String(v.value || "");
  }
  const audience = form.audience || "first-mate";
  const role = form.role === "ops" ? { role: "ops" } : {};
  if (form.kind === "stdio") {
    return { kind: "stdio", audience, ...role, command: String(form.command || "").trim(),
      args: String(form.args || "").split("\n").map((a) => a.trim()).filter(Boolean), env: values };
  }
  return { kind: form.kind, audience, ...role, url: String(form.url || "").trim(), headers: values };
};

const ipcError = (e) => ({ error: `IPC failed: ${e && e.message ? e.message : e}` });

/** Tests one server: `{ reached, tools, error }`, never throws. */
export const testServer = async (api, repo, name) => {
  try {
    const r = await api.testMcpServer(repo, name);
    return r || { reached: false, tools: [], error: "no answer from the main process" };
  } catch (e) { return { reached: false, tools: [], ...ipcError(e) }; }
};

/** Saves the form, then tests the server: `{ error }`, or `{ ok, name, test }`. */
export const saveServer = async (api, repo, form) => {
  const name = String(form.name || "").trim();
  if (!name) return { error: "a server needs a name" };
  let r;
  try { r = await api.saveMcpServer(repo, name, serverBody(form)); } catch (e) { r = ipcError(e); }
  if (!r || r.error) return { error: (r && (r.body || r.error)) || "no answer from the main process" };
  return { ok: true, name, test: await testServer(api, repo, name) };
};

/** Removes a server: `{ ok }` or `{ error }`. */
export const removeServer = async (api, repo, name) => {
  let r;
  try { r = await api.deleteMcpServer(repo, name); } catch (e) { r = ipcError(e); }
  return !r || r.error ? { error: (r && r.error) || "no answer from the main process" } : { ok: true };
};

/** An ops row's contract report ({version, conforms, missing}) after the test result; nothing for a plain row. */
const Contract = ({ contract }) => {
  if (!contract) return null;
  if (contract.conforms) return <span className="fmcContract fmcOk"> · conforms to {contract.version}</span>;
  return <span className="fmcContract acWarn"> · does not conform to {contract.version}: {(contract.missing || []).join("; ")}</span>;
};

/** A row's test result: testing, reached with its tools, or the error; an ops row's contract after it. */
export const TestResult = ({ result }) => {
  if (!result) return <span className="fmcResult acDim">not tested</span>;
  if (result.testing) return <span className="fmcResult acDim">testing…</span>;
  if (result.reached) {
    return <span className="fmcResult fmcOk">reached · {result.tools.length} tool{result.tools.length === 1 ? "" : "s"}
      {result.tools.length ? `: ${result.tools.join(", ")}` : ""}<Contract contract={result.contract} /></span>;
  }
  return <span className="fmcResult acWarn">not reached: {result.error || "no answer"}<Contract contract={result.contract} /></span>;
};

const where = (s) => (s.kind === "stdio" ? [s.command, ...(s.args || [])].join(" ") : s.url);

/** The server form: name (fixed once saved), kind, its fields, and the header or env values. */
export const ServerForm = ({ form, set, busy }) => {
  const stdio = form.kind === "stdio";
  const label = stdio ? "Environment" : "Headers";
  const setValue = (i, patch) => set("values", form.values.map((v, j) => (j === i ? { ...v, ...patch } : v)));
  return (
    <div className="fmcForm erControls">
      <label className="ecField">Name
        <input className="ecInput" value={form.name} disabled={busy || !form.isNew} spellCheck={false}
          onChange={(e) => set("name", e.target.value)} placeholder="docs" /></label>
      <label className="ecField">Kind
        <select className="ecInput" value={form.kind} disabled={busy} onChange={(e) => set("kind", e.target.value)}>
          {MCP_KINDS.map((k) => <option key={k} value={k}>{k}</option>)}
        </select></label>
      <label className="ecField">Who gets it
        <select className="ecInput" value={form.audience || "first-mate"} disabled={busy} onChange={(e) => set("audience", e.target.value)}>
          {MCP_AUDIENCES.map((a) => <option key={a.value} value={a.value}>{a.label}</option>)}
        </select></label>
      <label className="ecField">Role
        <select className="ecInput" value={form.role || ""} disabled={busy || stdio} onChange={(e) => set("role", e.target.value)}>
          {MCP_ROLES.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
        </select></label>
      {stdio
        ? <>
            <label className="ecField">Command
              <input className="ecInput" value={form.command} disabled={busy} spellCheck={false}
                onChange={(e) => set("command", e.target.value)} placeholder="npx" /></label>
            <label className="ecField">Arguments, one per line
              <textarea className="ecInput" rows={3} value={form.args} disabled={busy} spellCheck={false}
                onChange={(e) => set("args", e.target.value)} /></label>
          </>
        : <label className="ecField">URL
            <input className="ecInput" value={form.url} disabled={busy} spellCheck={false}
              onChange={(e) => set("url", e.target.value)} placeholder="https://mcp.example.com/mcp" /></label>}
      <div className="fmcValues">
        <div className="acDim">{label}</div>
        {form.values.map((v, i) => (
          <div key={i} className="fmcValue">
            <input className="ecInput" value={v.key} disabled={busy} spellCheck={false} placeholder="name"
              onChange={(e) => setValue(i, { key: e.target.value })} />
            <input className="ecInput" type={v.secret ? "password" : "text"} value={v.value} disabled={busy} spellCheck={false}
              placeholder={v.secret && v.set ? `set${v.since ? ` ${String(v.since).slice(0, 10)}` : ""}: blank keeps it` : "value"}
              onChange={(e) => setValue(i, { value: e.target.value })} />
            <label className="fmcSecret"><input type="checkbox" checked={v.secret} disabled={busy}
              onChange={(e) => setValue(i, { secret: e.target.checked, set: false })} /> secret</label>
            <button type="button" className="acBtn" disabled={busy} onClick={() => set("values", form.values.filter((_, j) => j !== i))}>✕</button>
          </div>
        ))}
        <button type="button" className="acBtn" disabled={busy}
          onClick={() => set("values", [...form.values, { key: "", value: "", secret: false, set: false, since: null }])}>+ {stdio ? "Variable" : "Header"}</button>
      </div>
    </div>
  );
};

/** The MCP servers page, with no state of its own: ConfigureFirstMateModal holds it. */
export const McpServersPage = ({ repo, servers, loadError, results, form, set, busy, error, onAdd, onEdit, onRemove, onTest, onSave, onCancelEdit }) => (
  <>
    <div className="erTitle">MCP servers</div>
    <p className="ecProse">Every First mate and every Smith run gets the engine's three MCP servers, frakworks,
      frakworks-engine-diagnostics and hitlp. The servers listed here are added beside them for {repo}, each for who it
      says: the First mate on this Mac, Smith runs on the cluster (which get an X-Frakworks-Run header naming the run),
      or both. Their tools are allowed (mcp__&lt;name&gt;__*). A change reaches the next First mate or Smith run started
      for this repo, not one already running. One server is often two rows: a laptop instance (127.0.0.1) for the First
      mate and a cluster instance for Smiths, since neither can reach the other's address.</p>
    <p className="ecProse">An http or sse server is tested by the engine; a stdio server runs on this machine, so the
      desktop starts it and does the handshake itself. A test runs after every save. A value marked secret is sealed by
      the engine and never shown again.</p>
    {loadError ? <div className="err">{loadError}</div> : null}
    {servers == null && !loadError ? <div className="acDim">asking the engine…</div> : null}
    {servers && !servers.length ? <div className="nmEmpty">No MCP servers for {repo} yet.</div> : null}
    {servers && servers.length ? (
      <ul className="fmcList">
        {servers.map((s) => (
          <li key={s.name} className="fmcRow">
            <span className="acName">{s.name}</span> <span className="acDim">{s.kind}</span> <span className="fmcAudience acDim">{audienceLabel(s.audience)}</span>{s.role ? <> <span className="fmcRole acDim">role {roleLabel(s.role)}</span></> : null} <span className="fmcWhere">{where(s)}</span>
            <TestResult result={results[s.name]} />
            <span className="acActions">
              <button type="button" className="acBtn" disabled={busy} onClick={() => onTest(s.name)}>Test</button>
              <button type="button" className="acBtn" disabled={busy} onClick={() => onEdit(s)}>Edit</button>
              <button type="button" className="acBtn danger" disabled={busy} onClick={() => onRemove(s.name)}>Remove</button>
            </span>
          </li>
        ))}
      </ul>
    ) : null}
    {form
      ? <form className="ecForm" onSubmit={(e) => { if (e) e.preventDefault(); onSave(); }}>
          <ServerForm form={form} set={set} busy={busy} />
          {error ? <div className="acProblem">{error}</div> : null}
          <div className="ecFoot">
            <button type="submit" className="acBtn" disabled={busy}>Save</button>
            <button type="button" className="acBtn" disabled={busy} onClick={onCancelEdit}>Cancel</button>
          </div>
        </form>
      : <button type="button" className="acBtn" disabled={busy || !servers} onClick={onAdd}>+ Add server</button>}
  </>
);

/** The modal's frame: title, tab row and the open tab's page. */
export const ConfigureFirstMateDialog = ({ repo, tab, onTab, onClose, children }) => (
  <ModalOverlay label={`Configure First Mate: ${repo}`} onEscape={onClose} className="erPanel">
    <div className="uoTitle">Configure First Mate: {repo}</div>
    <div className="erTabs" role="tablist">
      {CONFIG_TABS.map((t) => (
        <button key={t.key} type="button" role="tab" aria-selected={t.key === tab} className={"erTab" + (t.key === tab ? " on" : "")}
          onClick={() => onTab(t.key)}>{t.label}</button>
      ))}
    </div>
    <div className="erPage" role="tabpanel">
      {children}
      <div className="ecFoot"><button type="button" className="acBtn" onClick={onClose}>Close</button></div>
    </div>
  </ModalOverlay>
);

/** **Configure First Mate**: reads the repo's servers on open, and keeps each row's last test result. */
export const ConfigureFirstMateModal = ({ repo, onClose, api = typeof window !== "undefined" ? window.fleet : null }) => {
  const [tab, setTab] = useState("mcp");
  const [servers, setServers] = useState(null);
  const [loadError, setLoadError] = useState(null);
  const [results, setResults] = useState({});
  const [form, setForm] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const live = useRef(true);
  useEffect(() => () => { live.current = false; }, []);
  const load = async () => {
    let r;
    try { r = await api.mcpServers(repo); } catch (e) { r = ipcError(e); }
    if (!live.current) return;
    if (!r || r.error) { setLoadError((r && r.error) || "no answer from the main process"); return; }
    setLoadError(null); setServers(r.servers || []);
  };
  useEffect(() => { load(); }, [repo]);
  const result = (name, r) => { if (live.current) setResults((all) => ({ ...all, [name]: r })); };
  const onTest = async (name) => { result(name, { testing: true }); result(name, await testServer(api, repo, name)); };
  const onSave = async () => {
    setBusy(true); setError(null);
    const name = String(form.name || "").trim();
    if (name) result(name, { testing: true });
    const r = await saveServer(api, repo, form);
    if (!live.current) return;
    setBusy(false);
    if (r.error) { setError(r.error); if (name) result(name, undefined); return; }
    result(r.name, r.test); setForm(null); load();
  };
  const onRemove = async (name) => {
    setBusy(true);
    const r = await removeServer(api, repo, name);
    if (!live.current) return;
    setBusy(false);
    if (r.error) { setLoadError(r.error); return; }
    load();
  };
  return (
    <ConfigureFirstMateDialog repo={repo} tab={tab} onTab={setTab} onClose={onClose}>
      <McpServersPage repo={repo} servers={servers} loadError={loadError} results={results} form={form} busy={busy} error={error}
        set={(k, v) => setForm((f) => ({ ...f, [k]: v }))}
        onAdd={() => { setError(null); setForm(emptyForm()); }} onEdit={(s) => { setError(null); setForm(formOf(s)); }}
        onRemove={onRemove} onTest={onTest} onSave={onSave} onCancelEdit={() => { setError(null); setForm(null); }} />
    </ConfigureFirstMateDialog>
  );
};
