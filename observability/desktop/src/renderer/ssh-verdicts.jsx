// ssh-verdicts.jsx — the SSH verdicts page (Explorer > Analytics > SSH
// verdicts, #2439): the SSH judge's `ssh-judge` Jev calls newest first, what
// it was asked (host, command), what it answered (verdict, confidence, the
// rubric passage it cited, its reasoning) and the human decision of the HITLP
// task whose judge (its recommendation's by.judge, or context.data.judge)
// carries the call's callId, where one followed: decided on the HITLP server
// (#2487) and reported by the engine.
// Native React over GET /jev/ssh-verdicts (engine/README.md, "SSH verdicts"),
// through main (window.fleet.sshVerdicts); read-only. The table shares the
// Jev decisions page's classes (jev.jsx).
import { useCallback, useEffect, useRef, useState } from "react";
import { fmtAt, fmtConf } from "./jev.jsx";

const DASH = "–";

/** A verdict row's human decision as one word: approved, rejected, … or `pending` while its task is open; null without a task. */
export const decisionText = (d) => {
  if (!d) return null;
  if (d.outcome) return String(d.outcome);
  return d.status === "completed" ? "decided" : d.status === "working" || d.status === "input_required" ? "pending" : String(d.status || "pending");
};

/** The table's cells for one engine row, every missing field as `–`. */
export const verdictCells = (v) => ({
  id: v.id,
  when: fmtAt(v.called_at),
  host: v.host || DASH,
  command: v.command || DASH,
  verdict: v.verdict === "no-answer" ? "no answer" : v.verdict || DASH,
  confidence: fmtConf(v.confidence),
  passage: v.cited_passage || DASH,
  reasoning: v.reasoning || DASH,
  decision: decisionText(v.decision) || DASH,
});

export const SshVerdictsTable = ({ verdicts }) => (
  <table className="jevCalls sshVerdicts">
    <thead>
      <tr>
        <th>called</th><th>host</th><th>command</th><th>verdict</th><th className="num">conf.</th>
        <th>cited passage</th><th>reasoning</th><th>decision</th>
      </tr>
    </thead>
    <tbody>
      {verdicts.map(verdictCells).map((c) => (
        <tr key={c.id} className={"jevRow" + (c.verdict === "no answer" ? " noAnswer" : "")}>
          <td className="jevWhen">{c.when}</td>
          <td>{c.host}</td>
          <td className="jevText"><code>{c.command}</code></td>
          <td>{c.verdict}</td>
          <td className="num">{c.confidence}</td>
          <td className="jevText" title={c.passage}>{c.passage}</td>
          <td className="jevText" title={c.reasoning}>{c.reasoning}</td>
          <td>{c.decision}</td>
        </tr>
      ))}
    </tbody>
  </table>
);

export const SshVerdictsView = ({ result }) => {
  const r = result || { loading: true };
  return (
    <div className="fleet-root jev">
      <div className="head"><h1>SSH verdicts</h1></div>
      {r.loading && <div className="muted">asking the engine for the SSH judge's verdicts…</div>}
      {r.error && <div className="err">{String(r.error)}</div>}
      {r.verdicts && (r.verdicts.length
        ? <SshVerdictsTable verdicts={r.verdicts} />
        : <div className="muted">The SSH judge has made no call yet.</div>)}
    </div>
  );
};

export const SshVerdicts = ({ repo }) => {
  const [result, setResult] = useState({ loading: true });
  const seq = useRef(0);
  const load = useCallback(async () => {
    const mine = ++seq.current;
    if (!(typeof window !== "undefined" && window.fleet && window.fleet.sshVerdicts)) {
      setResult({ error: "this build has no SSH verdicts read" });
      return;
    }
    let r;
    try { r = await window.fleet.sshVerdicts(repo || null); }
    catch (e) { r = { error: String(e && e.message || e) }; }
    if (mine === seq.current) setResult(r);
  }, [repo]);
  useEffect(() => { load(); return () => { seq.current++; }; }, [load]);
  return <SshVerdictsView result={result} />;
};
