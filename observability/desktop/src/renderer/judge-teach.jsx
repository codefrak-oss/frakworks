// judge-teach.jsx — Allow and teach / Deny and teach (#2436) on a judge
// escalation's card, the urgent popup's and the Needs row's alike: a click
// decides the task through the card's own `decide` (as Approve or Reject does,
// on the HITLP server),
// then `window.fleet.teachJudge(taskId, allow|deny, note)` asks the engine's
// `POST /judge/teach` to commit the line, with the first-mate token main keeps.
// Without that token both buttons are disabled with a tooltip saying why.

import { useEffect, useState } from "react";
import { TEACH_BUTTONS, judgeOf, teachTitle, teachClick } from "../judge-teach.js";

const bridge = (name) => (...a) => (typeof window !== "undefined" && window.fleet && window.fleet[name]
  ? window.fleet[name](...a) : Promise.resolve({ error: "no engine bridge" }));

/**
 * Nothing unless `row` is a judge escalation. `decide(taskId, outcome, answer,
 * reason)` and `teach(taskId, decision, note)` stand in for the bridge (tests);
 * `ready` pins whether a first-mate token is set (else main is asked once).
 * `note` is the card's reason or comment. `onDone(line)` after both succeed;
 * `onError(text)` for either's error. `className` is the card's button class.
 */
export const TeachButtons = ({ row, decide, teach = null, ready = null, note = "", busy = false, setBusy = () => {},
  onDone = () => {}, onError = () => {}, className = "acBtn nmBtn" }) => {
  const judge = judgeOf(row);
  const [hasToken, setHasToken] = useState(ready);
  useEffect(() => {
    if (!judge || ready != null) return undefined;
    let live = true;
    bridge("teachTokenStatus")().then((s) => { if (live) setHasToken(Boolean(s && s.signedIn)); }, () => { if (live) setHasToken(false); });
    return () => { live = false; };
  }, [row && row.actionId, ready]);
  if (!judge) return null;
  const ok = Boolean(ready != null ? ready : hasToken);
  const send = async (b) => {
    setBusy(true); onError(null);
    try {
      const r = await teachClick(row, b, note, decide, teach || bridge("teachJudge"));
      if (r.ok) onDone(r.line); else onError(r.error);
    } catch (e) { onError(e && e.message ? e.message : String(e)); }
    setBusy(false);
  };
  // Where the line lands, on the card itself, so the human sees it before clicking.
  const target = <span key="target" className="teachTarget" data-teach-target="">teaches {judge.rubricPath || "?"} in {judge.repo || "?"}</span>;
  return [target, ...TEACH_BUTTONS.map((b) => (
    <button key={b.decision} type="button" disabled={busy || !ok} data-teach={b.decision} title={teachTitle(judge, ok)}
      className={className + " teach " + (b.decision === "allow" ? "approve" : "reject")} onClick={() => send(b)}>{b.label}</button>
  ))];
};
