// find.jsx — the find-in-page bar (#703). Edit > Find (CmdOrCtrl+F,
// src/app-menu.js) shows it over the window's top right.
//
// It is not part of the page it searches: main lays it over the window as its
// own WebContentsView (find.html, built to dist/find.js). In the page,
// Chromium's find matched the bar's own input and took the input's focus and
// caret on every search, so fast typing lost keys. Here the input is never
// searched and keeps its focus.
//
// The bar reaches main only through window.app (src/preload.js):
// `find(text, { forward, findNext })` runs webContents.findInPage on the main
// window, `stopFind()` clears the highlights and hides the bar, and `onFind`
// hears `find:update`: `{ open: true }` when Cmd-F is pressed (focus the
// input, select its text) and `{ active, matches }` from each found-in-page.
import { useEffect, useReducer, useRef } from "react";
import { createRoot } from "react-dom/client";

export const FIND_CLOSED = { open: false, text: "", active: 0, matches: 0, focus: 0 };

// One step of the bar: the state after `action`, and the call to make on
// window.app, `["find", text, opts]`, `["stop"]` or null.
//   open    Cmd-F: show, focus and select; searches the kept text again
//   text    the input changed: a new search, or stop on an empty field
//   next    Enter; prev Shift-Enter: the following or previous match
//   close   Escape or the close button: stop and clear the highlights
//   result  a found-in-page: the count, "n of m"
export function findStep(state, action) {
  switch (action.type) {
    case "open": {
      const s = { ...state, open: true, focus: state.focus + 1 };
      if (state.open || !state.text) return { state: s, call: null };
      return { state: { ...s, active: 0, matches: 0 }, call: ["find", state.text, { forward: true, findNext: true }] };
    }
    case "text": {
      const text = String(action.text);
      const s = { ...state, text, active: 0, matches: 0 };
      return { state: s, call: text ? ["find", text, { forward: true, findNext: true }] : ["stop"] };
    }
    case "next":
    case "prev":
      if (!state.text) return { state, call: null };
      return { state, call: ["find", state.text, { forward: action.type === "next", findNext: false }] };
    case "close":
      // The text stays, as a browser's does, for the next Cmd-F.
      return { state: { ...state, open: false, active: 0, matches: 0 }, call: ["stop"] };
    case "result": {
      if (!state.text) return { state, call: null };
      const n = (v, old) => (Number.isInteger(v) && v >= 0 ? v : old);
      const matches = n(action.matches, state.matches);
      const active = Math.min(n(action.active, state.active), matches);
      return { state: { ...state, active, matches }, call: null };
    }
    default:
      return { state, call: null };
  }
}

// "n of m" once there is something to look for; "0 of 0" when nothing matches.
export const countText = (state) => (state.text ? `${state.active} of ${state.matches}` : "");

// Which action a key in the input is: Enter, Shift-Enter, Escape.
export function keyAction(e) {
  if (e.key === "Escape") return "close";
  if (e.key === "Enter") return e.shiftKey ? "prev" : "next";
  return null;
}

export function FindBar({ api }) {
  // The reducer keeps only the state; the call it asks for is made from
  // `dispatch` below, once per action, never from inside the reducer.
  const stateRef = useRef(FIND_CLOSED);
  const [state, set] = useReducer((_s, next) => next, FIND_CLOSED);
  const input = useRef(null);
  const dispatch = (action) => {
    const { state: next, call } = findStep(stateRef.current, action);
    stateRef.current = next;
    set(next);
    if (!call || !api) return;
    if (call[0] === "find") api.find(call[1], call[2]);
    else api.stopFind();
  };
  useEffect(() => {
    if (!api || !api.onFind) return undefined;
    return api.onFind((u) => {
      if (u && u.open) dispatch({ type: "open" });
      else if (u) dispatch({ type: "result", active: u.active, matches: u.matches });
    });
  }, []);
  useEffect(() => {
    if (!state.focus || !input.current) return;
    input.current.focus();
    input.current.select();
  }, [state.focus]);
  const onKeyDown = (e) => {
    const a = keyAction(e);
    if (!a) return;
    e.preventDefault();
    dispatch({ type: a });
  };
  return (
    <div className="findBar" role="search">
      <input ref={input} className="findInput" type="text" placeholder="Find in page" spellCheck={false}
             aria-label="Find in page" value={state.text}
             onChange={(e) => dispatch({ type: "text", text: e.target.value })} onKeyDown={onKeyDown} />
      <span className="findCount">{countText(state)}</span>
      <button className="findBtn" title="Previous match (Shift-Enter)" aria-label="Previous match"
              disabled={!state.matches} onClick={() => dispatch({ type: "prev" })}>{"↑"}</button>
      <button className="findBtn" title="Next match (Enter)" aria-label="Next match"
              disabled={!state.matches} onClick={() => dispatch({ type: "next" })}>{"↓"}</button>
      <button className="findBtn" title="Close (Escape)" aria-label="Close" onClick={() => dispatch({ type: "close" })}>{"×"}</button>
    </div>
  );
}

if (typeof document !== "undefined" && document.getElementById("find-root")) {
  createRoot(document.getElementById("find-root")).render(<FindBar api={window.app} />);
}
