// modal-overlay.jsx — the one modal style (#1252): a fixed scrim over the
// whole window and a panel on it, the `uoBackdrop` / `uoPanel` rules in
// styles.css. The Update engine overlay, the Health check overlay and
// Config > Repos' Edit… modal (#1255) render through it;
// `className` adds to the panel's (the Edit… modal's wider `erPanel`).
//
// While it is open everything outside it is `inert` (inertOutside), and,
// given `onEscape`, the Escape key calls it; given `onBackdrop`, a click on
// the scrim itself (not the panel) calls it (Config > Jobs' Register modal, #1665).
// `behind` renders in the scrim under the panel and `backdropClassName` adds
// to the scrim's class (the update preview's blurred snapshot, #100091).

import { useEffect, useRef } from "react";

/**
 * Marks every sibling of `node` and of each of its ancestors up to the body
 * `inert`, so only the overlay takes focus and clicks; returns the undo,
 * which clears only what it set.
 */
export const inertOutside = (node) => {
  const set = [];
  for (let n = node; n && n.parentElement; n = n.parentElement) {
    for (const s of Array.from(n.parentElement.children)) {
      if (s !== n && !s.inert) { s.inert = true; set.push(s); }
    }
    if (n.parentElement.tagName === "BODY") break;
  }
  return () => { for (const s of set) s.inert = false; };
};

/** Calls `fn` when the click landed on the scrim itself, not inside the panel. */
export const backdropClick = (e, fn) => { if (fn && e && e.target === e.currentTarget) fn(); };

export const ModalOverlay = ({ label, onEscape, onBackdrop, className, backdropClassName, behind = null, children }) => {
  const ref = useRef(null);
  useEffect(() => (ref.current ? inertOutside(ref.current) : undefined), []);
  useEffect(() => {
    if (!onEscape) return undefined;
    const onKey = (e) => { if (e && e.key === "Escape") onEscape(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onEscape]);
  return (
    <div className={"uoBackdrop" + (backdropClassName ? ` ${backdropClassName}` : "")} role="dialog" aria-modal="true" aria-label={label} ref={ref}
         onClick={onBackdrop ? (e) => backdropClick(e, onBackdrop) : undefined}>
      {behind}
      <div className={"uoPanel" + (className ? ` ${className}` : "")}>{children}</div>
    </div>
  );
};
