// process.jsx — the process view: a ticket's process instances the way Cockpit
// shows them. `ProcessSection` lists every instance the engine tied to the
// issue (`GET /camunda/instances?issueUrl=`); opening one draws its diagram
// with bpmn-js, the completed steps, the active ones and any incident marked
// (`GET /camunda/instances/<id>` for the tree, `/camunda/definitions/<id>/xml`
// for the diagram of the version it runs). Clicking a step opens what the
// engine knows about it: its incident, the variables in its scope, and the
// process it called, which the breadcrumb then opens.
//
// The three reads are window.fleet.process* (src/engine-data.js). The one
// write is an incident's Retry (`RetryIncident`, window.fleet.retryIncident),
// behind a confirm; nothing else here writes anything. The pure parts
// (`markersOf`, `instanceState`, `ProcessList`, `ElementPanel`) take their
// data as props so a test can render them without an engine; bpmn-js itself
// is imported lazily inside `Diagram`, so the test bundle never touches the DOM.
import { useEffect, useMemo, useRef, useState } from "react";
import { fmtAgo } from "./issues.jsx";
import { useRefetchOn, coalesce } from "./engine-events.js";
import "bpmn-js/dist/assets/diagram-js.css";

/** "running · at <step>", "incident" or "ended", from a list row. */
export const instanceState = (inst) => {
  if (!inst) return "";
  if (inst.state === "incident") return "incident";
  if (inst.ended || inst.state === "ended") return "ended";
  return inst.step ? `running · at ${inst.step}` : "running";
};

/**
 * Which activity gets which marker, from one node of the tree: an incident
 * beats active, active beats completed (a loop that re-entered a step is at
 * it now, not done with it). Sets of activity ids.
 */
export const markersOf = (node) => {
  const incident = new Set((node && node.incidents || []).map((i) => i.activityId).filter(Boolean));
  const active = new Set((node && node.active || []).filter((a) => !incident.has(a)));
  const completed = new Set((node && node.completed || []).filter((a) => !active.has(a) && !incident.has(a)));
  return { completed, active, incident };
};

/** The child process a call activity of `node` started, if the tree has it. */
export const calledChild = (tree, node, activityId) =>
  ((tree && tree.nodes) || []).find((n) => n.parentId === node.id && n.calledFrom === activityId) || null;

/** The root, then each ancestor of `node` down to it: what the breadcrumb draws. */
export const pathTo = (tree, node) => {
  const byId = new Map(((tree && tree.nodes) || []).map((n) => [n.id, n]));
  const path = [];
  for (let n = node; n; n = n.parentId ? byId.get(n.parentId) : null) path.unshift(n);
  return path;
};

export const nodeTitle = (n) => (n && (n.processName || n.processDefinitionKey)) || "";

/** One row per instance; the open one is marked. */
export const ProcessList = ({ instances, open, onOpen, now }) => {
  if (!instances.length) return <div className="muted">no process instances for this ticket</div>;
  return (
    <table className="pvList">
      <tbody>
        {instances.map((i) => (
          <tr key={i.id} className={(open === i.id ? "open " : "") + "is-" + (i.state || "running")}
              onClick={() => onOpen && onOpen(open === i.id ? null : i.id)}
              title={(open === i.id ? "Hide the diagram" : "Show the diagram") + ` · ${i.id}`}>
            <td className="pvChev">{open === i.id ? "▾" : "▸"}</td>
            <td className="pvProc">{i.processName || i.processDefinitionKey}</td>
            <td className="pvState">{instanceState(i)}</td>
            <td className="num muted">
              {i.endedAt ? `ended ${fmtAgo(i.endedAt, now)} ago` : i.startedAt ? `started ${fmtAgo(i.startedAt, now)} ago` : ""}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
};

const fmtValue = (v) => {
  if (v == null) return "null";
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
};

/**
 * An incident's Retry: one click asks, a second sends. The engine decides
 * what a retry is (the failed job again, or a failed Smith run started
 * over); `onRetried` reloads the tree so the incident goes or comes back.
 */
export const RetryIncident = ({ incident, onRetried }) => {
  const [phase, setPhase] = useState("idle"); // idle | confirm | sending | done
  const [error, setError] = useState(null);
  const send = async () => {
    setPhase("sending"); setError(null);
    let r;
    try { r = await window.fleet.retryIncident(incident.id); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    if (r && r.error) { setError(r.error); setPhase("idle"); return; }
    setPhase("done");
    if (onRetried) onRetried();
  };
  return (
    <div className="pvRetry">
      {phase === "idle" && <button className="pvButton" onClick={() => setPhase("confirm")}>Retry</button>}
      {phase === "confirm" && <>
        <span>Retry this incident?</span>
        <button className="pvButton is-go" onClick={send}>Retry</button>
        <button className="pvButton" onClick={() => setPhase("idle")}>Cancel</button>
      </>}
      {phase === "sending" && <span className="muted">retrying…</span>}
      {phase === "done" && <span className="muted">retried; the engine is on it</span>}
      {error && <div className="err">{error}</div>}
    </div>
  );
};

/**
 * What the engine knows about one element of the open process: its state,
 * its incident, the variables set in its scope, and the process it called.
 * `element` is what the viewer handed over on click: { id, name, type }.
 */
export const ElementPanel = ({ tree, node, element, onOpenNode, onRetried }) => {
  if (!element) return <div className="muted pvHint">click a step for its incident, variables and called process</div>;
  const m = markersOf(node);
  const state = m.incident.has(element.id) ? "incident" : m.active.has(element.id) ? "active"
    : m.completed.has(element.id) ? "completed" : "not reached";
  const incidents = (node.incidents || []).filter((i) => i.activityId === element.id);
  const vars = (node.variables || []).filter((v) => v.scope === element.id);
  const timers = (node.timers || []).filter((t) => t.activityId === element.id);
  const child = calledChild(tree, node, element.id);
  return (
    <div className="pvPanel">
      <div className="pvPanelHead">
        <b>{element.name || element.id}</b>
        <span className={"pvMark is-" + state.replace(" ", "-")}>{state}</span>
      </div>
      {incidents.map((i) => (
        <div key={i.id} className="err pvIncident">
          <b>{i.type}</b>{i.createdAt ? <span className="muted"> · {i.createdAt}</span> : null}
          <pre className="flText">{i.message || "(no message)"}</pre>
          <RetryIncident incident={i} onRetried={onRetried} />
        </div>
      ))}
      {child && (
        <div className="pvCalled">
          called <button className="pvLink" onClick={() => onOpenNode && onOpenNode(child)}>{nodeTitle(child)}</button>
          <span className="muted"> · {child.ended ? "ended" : "running"}</span>
        </div>
      )}
      {timers.map((t, k) => <div key={k} className="pvWait">waits until {t.dueAt}</div>)}
      {vars.length > 0 && (
        <table className="flVars"><tbody>
          {vars.map((v) => (
            <tr key={v.name}><td className="flProc" title={v.type || ""}>{v.name}</td>
              <td className="pvValue">{fmtValue(v.value)}{v.truncated ? <span className="muted"> … (cut)</span> : null}</td></tr>
          ))}
        </tbody></table>
      )}
      {!incidents.length && !child && !vars.length && !timers.length && <div className="muted">nothing set in this step's scope</div>}
    </div>
  );
};

/**
 * The variables in the process's own scope (no activity), the way Cockpit lists
 * them under the diagram, folded behind a "Camunda variables" heading like the
 * comments fold in ticket.jsx. Closed until the viewer opens it; remembered.
 */
const VARIABLES_KEY = "ticket.variablesOpen";

export const VariablesFold = ({ vars, open, flip }) => (
  <>
    <h2 className="tdFold" onClick={flip} title={open ? "Hide the variables" : "Show the variables"}>
      <span className="tdFoldArrow">{open ? "▾" : "▸"}</span>
      Camunda variables
      <span className="tdFoldCount">{vars.length}</span>
    </h2>
    {open && (
      <table className="flVars"><tbody>
        {vars.map((v) => (
          <tr key={v.name}><td className="flProc" title={v.type || ""}>{v.name}</td>
            <td className="pvValue">{fmtValue(v.value)}{v.truncated ? <span className="muted"> … (cut)</span> : null}</td></tr>
        ))}
      </tbody></table>
    )}
  </>
);

export const ProcessVariables = ({ node }) => {
  const [open, setOpen] = useState(() => {
    try { return localStorage.getItem(VARIABLES_KEY) === "true"; } catch (e) { return false; }
  });
  const vars = (node.variables || []).filter((v) => !v.scope);
  if (!vars.length) return null;
  const flip = () => {
    setOpen(!open);
    try { localStorage.setItem(VARIABLES_KEY, String(!open)); } catch (e) { /* per-viewer nicety only */ }
  };
  return <VariablesFold vars={vars} open={open} flip={flip} />;
};

// ---- the diagram ------------------------------------------------------------

/**
 * Zoom to the flow itself. bpmn-js's fit-viewport takes the whole drawing,
 * and a diagram's note (a text annotation parked well below the flow, the
 * Modeler convention in engine/bpmn/) would shrink the steps to nothing.
 */
const fitFlow = (v) => {
  const canvas = v.get("canvas");
  const shapes = v.get("elementRegistry").filter((e) =>
    !e.waypoints && e.type !== "label" && e.type !== "bpmn:Process" && e.type !== "bpmn:TextAnnotation" && e.parent);
  if (!shapes.length) { canvas.zoom("fit-viewport", "auto"); return; }
  const x1 = Math.min(...shapes.map((s) => s.x)), y1 = Math.min(...shapes.map((s) => s.y));
  const x2 = Math.max(...shapes.map((s) => s.x + s.width)), y2 = Math.max(...shapes.map((s) => s.y + s.height));
  const pad = 30;
  canvas.viewbox({ x: x1 - pad, y: y1 - pad, width: x2 - x1 + 2 * pad, height: y2 - y1 + 2 * pad });
};

/**
 * bpmn-js's NavigatedViewer on `xml`, with the node's markers on top. The
 * viewer is created once per XML and the markers are re-applied on every
 * node change, so a 15 s poll moves the highlight without redrawing.
 */
const Diagram = ({ xml, node, onSelect }) => {
  const box = useRef(null);
  const viewer = useRef(null);
  const marked = useRef(new Map());
  const [ready, setReady] = useState(0);
  const [error, setError] = useState(null);

  useEffect(() => {
    let dead = false;
    setError(null);
    (async () => {
      let Viewer;
      try { Viewer = (await import("bpmn-js/lib/NavigatedViewer")).default; }
      catch (e) { if (!dead) setError(`bpmn-js failed to load: ${e && e.message ? e.message : e}`); return; }
      if (dead || !box.current) return;
      const v = new Viewer({ container: box.current });
      viewer.current = v;
      marked.current = new Map();
      try {
        await v.importXML(xml);
        if (dead) return;
        fitFlow(v);
        v.on("element.click", (e) => {
          const el = e.element, bo = el && el.businessObject;
          if (!el || !bo || el.type === "bpmn:Process" || el.type === "label") return;
          onSelect && onSelect({ id: el.id, name: bo.name ? String(bo.name).replace(/\s*\n\s*/g, " ") : null, type: el.type });
        });
        setReady((n) => n + 1);
      } catch (e) { if (!dead) setError(`the diagram did not import: ${e && e.message ? e.message : e}`); }
    })();
    return () => { dead = true; if (viewer.current) { try { viewer.current.destroy(); } catch { /* gone */ } viewer.current = null; } };
  }, [xml]);

  useEffect(() => {
    const v = viewer.current;
    if (!v || !ready) return;
    const canvas = v.get("canvas"), registry = v.get("elementRegistry"), overlays = v.get("overlays");
    const m = markersOf(node);
    const want = new Map();
    for (const id of m.completed) want.set(id, "pv-completed");
    for (const id of m.active) want.set(id, "pv-active");
    for (const id of m.incident) want.set(id, "pv-incident");
    for (const [id, cls] of marked.current) if (want.get(id) !== cls && registry.get(id)) canvas.removeMarker(id, cls);
    for (const [id, cls] of want) if (marked.current.get(id) !== cls && registry.get(id)) canvas.addMarker(id, cls);
    marked.current = want;
    overlays.clear();
    for (const i of node.incidents || []) {
      if (!i.activityId || !registry.get(i.activityId)) continue;
      const html = document.createElement("div");
      html.className = "pvBadge";
      html.title = i.message || i.type;
      html.textContent = "!";
      overlays.add(i.activityId, "pv-incident", { position: { top: -8, right: -8 }, html });
    }
    // An active timer (reusable-smith-run's event-wait-reset): its due instant as the engine sends it.
    for (const t of node.timers || []) {
      if (!t.activityId || !registry.get(t.activityId)) continue;
      const html = document.createElement("div");
      html.className = "pvWait";
      html.textContent = `waits until ${t.dueAt}`;
      overlays.add(t.activityId, "pv-timer", { position: { bottom: -4, left: 0 }, html });
    }
  }, [node, ready]);

  return (
    <div className="pvDiagram">
      <div ref={box} className="pvCanvas" />
      {error && <div className="err">{error}</div>}
    </div>
  );
};

// ---- one open instance ------------------------------------------------------

export const ProcessInstance = ({ id, now }) => {
  const [result, setResult] = useState({ loading: true });
  const [nodeId, setNodeId] = useState(null);
  const [element, setElement] = useState(null);
  const [xmls, setXmls] = useState({});

  // `coalesce`d: a frame's re-read during a read runs after it, never dropped.
  const load = useMemo(() => coalesce(async () => {
    let r;
    try { r = await window.fleet.processTree(id); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setResult(r);
  }), [id]);

  useEffect(() => { setResult({ loading: true }); setNodeId(null); setElement(null); load(); }, [id, load]);
  const tree = result.tree || null;
  // Re-read on every `flow` frame of this instance, as the root or the called one.
  useRefetchOn(["flow"], load, { instance: id });

  const node = tree && (tree.nodes.find((n) => n.id === nodeId) || tree.nodes[0]) || null;
  const definitionId = node && node.processDefinitionId;
  useEffect(() => {
    if (!definitionId || xmls[definitionId]) return;
    let dead = false;
    (async () => {
      let r;
      try { r = await window.fleet.processXml(definitionId); }
      catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
      if (!dead) setXmls((x) => ({ ...x, [definitionId]: r }));
    })();
    return () => { dead = true; };
  }, [definitionId, xmls]);

  if (result.loading) return <div className="muted">asking the engine for the process…</div>;
  if (result.error) return <div className="err">{result.error}</div>;
  if (!node) return <div className="muted">the engine has no processes for this instance</div>;
  const xml = xmls[definitionId];
  const path = pathTo(tree, node);
  const open = (n) => { setNodeId(n.id); setElement(null); };
  return (
    <div className="pvInstance">
      <div className="pvCrumbs">
        {path.map((n, i) => (
          <span key={n.id}>
            {i > 0 && <span className="muted"> › </span>}
            {n.id === node.id ? <b>{nodeTitle(n)}</b> : <button className="pvLink" onClick={() => open(n)}>{nodeTitle(n)}</button>}
          </span>
        ))}
        <span className="muted pvCrumbState"> · {node.ended ? `ended ${fmtAgo(node.endedAt, now)} ago` : "running"}
          {node.incidents && node.incidents.length ? ` · ${node.incidents.length} incident${node.incidents.length === 1 ? "" : "s"}` : ""}</span>
      </div>
      {!xml && <div className="muted">fetching the diagram…</div>}
      {xml && xml.error && <div className="err">{xml.error}</div>}
      {xml && xml.xml && <Diagram xml={xml.xml} node={node} onSelect={setElement} />}
      <ElementPanel tree={tree} node={node} element={element} onOpenNode={open} onRetried={load} />
      <ProcessVariables node={node} />
    </div>
  );
};

// ---- the section --------------------------------------------------------------

/**
 * The "Processes" section of a ticket's or issue's detail: the instances the
 * engine tied to `issueUrl`, re-read on its `flow` frames, and the open one's diagram.
 */
// Folds behind its heading like the Doctor section (flow.jsx DOCTOR_KEY),
// collapsed by default and remembered per viewer under its own key.
const PROCESSES_KEY = "ticket.processesOpen";

// Asks the engine for the instances (and re-reads them on `flow`) whether or
// not the fold is open, so opening it shows the current list at once.
export const ProcessSection = ({ issueUrl, now }) => {
  const [result, setResult] = useState({ loading: true });
  const [open, setOpen] = useState(null);
  const [shown, setShown] = useState(() => {
    try { return localStorage.getItem(PROCESSES_KEY) === "true"; } catch (e) { return false; }
  });
  const flipShown = () => {
    setShown(!shown);
    try { localStorage.setItem(PROCESSES_KEY, String(!shown)); } catch (e) { /* per-viewer nicety only */ }
  };
  const load = useMemo(() => coalesce(async () => {
    if (!issueUrl) return;
    let r;
    try { r = await window.fleet.processInstances(issueUrl); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}`, instances: [] }; }
    setResult(r);
  }), [issueUrl]);
  useEffect(() => { setResult({ loading: true }); setOpen(null); autoOpened.current = false; load(); }, [issueUrl, load]);
  const instances = result.instances || [];
  // The first answer opens the newest instance still running (else the newest
  // there is) so the diagram is on screen without a click; once, so closing it
  // sticks across the polls.
  const autoOpened = useRef(false);
  useEffect(() => {
    if (autoOpened.current || result.loading || !instances.length) return;
    autoOpened.current = true;
    setOpen((instances.find((i) => !i.ended) || instances[0]).id);
  }, [result, instances]);
  // Re-read on every `flow` frame of this issue (a root started, stepped or ended).
  useRefetchOn(["flow"], load, { issueUrl });
  const at = now != null ? now : Date.now();
  return (
    <>
      <h2 className="tdFold" onClick={flipShown} title={shown ? "Hide the processes" : "Show the processes"}>
        <span className="tdFoldArrow">{shown ? "▾" : "▸"}</span>
        Processes
      </h2>
      {shown && result.loading && <div className="muted">asking the engine for the processes…</div>}
      {shown && result.error && <div className="err">{result.error}</div>}
      {shown && !result.loading && !result.error && <ProcessList instances={instances} open={open} onOpen={setOpen} now={at} />}
      {shown && open && <ProcessInstance id={open} now={at} />}
    </>
  );
};
