// Draggable vertical splitters between the explorer, the work area and the
// First mate strip (#361). The width logic is pure so the tests can reach it;
// the widths persist per viewer in localStorage, like ticket.jsx's toggles.
import { useRef } from "react";

export const DEFAULT_WIDTH = 260;
export const MIN_SIDE_WIDTH = 160;
export const MIN_WORK_WIDTH = 320;
export const SPLITTER_WIDTH = 4;
export const EXPLORER_KEY = "shell.explorerWidth";
export const FIRST_MATE_KEY = "shell.firstMateWidth";

// Clamp one side column: at least MIN_SIDE_WIDTH, and never so wide that .work
// (window minus the other column and both bars) drops under MIN_WORK_WIDTH.
export const clampWidth = (width, total, other) => {
  const max = Math.max(MIN_SIDE_WIDTH, total - other - 2 * SPLITTER_WIDTH - MIN_WORK_WIDTH);
  const w = Number.isFinite(width) ? width : DEFAULT_WIDTH;
  return Math.round(Math.min(max, Math.max(MIN_SIDE_WIDTH, w)));
};

// The width after dragging `dx` pixels from `start`. The right column grows as
// the pointer moves left.
export const dragWidth = (start, dx, side) => side === "right" ? start - dx : start + dx;

export const parseWidth = (raw) => {
  const n = Number(raw);
  return raw != null && raw !== "" && Number.isFinite(n) && n > 0 ? n : DEFAULT_WIDTH;
};
export const readWidth = (key) => { try { return parseWidth(localStorage.getItem(key)); } catch (e) { return DEFAULT_WIDTH; } };
export const saveWidth = (key, w) => { try { localStorage.setItem(key, String(w)); } catch (e) { /* per-viewer nicety only */ } };

// One bar. `onWidth(start, dx)` is called on every move; pointer capture keeps
// the moves coming over the Grafana iframe, and the body
// class turns off iframe pointer events and text selection while dragging.
export const Splitter = ({ side, width, onDrag, onEnd, onReset }) => {
  const drag = useRef(null);
  const stop = (e) => {
    if (!drag.current) return;
    drag.current = null;
    try { e.currentTarget.releasePointerCapture(e.pointerId); } catch (err) { /* already released */ }
    document.body.classList.remove("colResizing");
    onEnd && onEnd();
  };
  return (
    <div className={`splitter splitter-${side}`} role="separator" aria-orientation="vertical"
         aria-valuenow={width} title="Drag to resize; double-click to reset"
         onPointerDown={(e) => {
           if (e.button !== 0) return;
           e.preventDefault();
           drag.current = { x: e.clientX, start: width };
           e.currentTarget.setPointerCapture(e.pointerId);
           document.body.classList.add("colResizing");
         }}
         onPointerMove={(e) => { if (drag.current) onDrag(drag.current.start, e.clientX - drag.current.x); }}
         onPointerUp={stop} onPointerCancel={stop} onLostPointerCapture={stop}
         onDoubleClick={onReset} />
  );
};

// The horizontal bar between the repo file view's two Markdown panels (#675):
// the same clamp/drag/persist logic, turned to heights. `height` is the top
// (rendered) panel's; the raw source below takes the rest. Nothing stored
// means an even split of whatever height the view has.
export const MIN_PANEL_HEIGHT = 80;
export const SPLITTER_HEIGHT = 4;
export const MARKDOWN_SPLIT_KEY = "repoFile.markdownSplitHeight";

// The top panel's height in a split `total` pixels tall: at least
// MIN_PANEL_HEIGHT, and never so tall that the bottom panel drops under it.
// No height (nothing stored, or garbage) is the even split.
export const clampHeight = (height, total) => {
  const room = Math.max(0, (Number.isFinite(total) ? total : 0) - SPLITTER_HEIGHT);
  const max = Math.max(MIN_PANEL_HEIGHT, room - MIN_PANEL_HEIGHT);
  const h = Number.isFinite(height) ? height : room / 2;
  return Math.round(Math.min(max, Math.max(MIN_PANEL_HEIGHT, h)));
};

// The top panel's height after dragging `dy` pixels from `start`: it grows as
// the pointer moves down.
export const dragHeight = (start, dy) => start + dy;

export const parseHeight = (raw) => {
  const n = Number(raw);
  return raw != null && raw !== "" && Number.isFinite(n) && n > 0 ? n : null;
};
export const readHeight = (key) => { try { return parseHeight(localStorage.getItem(key)); } catch (e) { return null; } };
// Saving no height (a double-click reset) forgets it: the even split again.
export const saveHeight = (key, h) => {
  try { if (h == null) localStorage.removeItem(key); else localStorage.setItem(key, String(h)); } catch (e) { /* per-viewer nicety only */ }
};

// One horizontal bar, the vertical Splitter turned: `onDrag(start, dy)` on
// every move, `onEnd` when the drag ends, `onReset` on a double-click.
export const HSplitter = ({ height, onDrag, onEnd, onReset }) => {
  const drag = useRef(null);
  const stop = (e) => {
    if (!drag.current) return;
    drag.current = null;
    try { e.currentTarget.releasePointerCapture(e.pointerId); } catch (err) { /* already released */ }
    document.body.classList.remove("rowResizing");
    onEnd && onEnd();
  };
  return (
    <div className="hsplitter" role="separator" aria-orientation="horizontal"
         aria-valuenow={height} title="Drag to resize; double-click to reset"
         onPointerDown={(e) => {
           if (e.button !== 0) return;
           e.preventDefault();
           drag.current = { y: e.clientY, start: height };
           e.currentTarget.setPointerCapture(e.pointerId);
           document.body.classList.add("rowResizing");
         }}
         onPointerMove={(e) => { if (drag.current) onDrag(drag.current.start, e.clientY - drag.current.y); }}
         onPointerUp={stop} onPointerCancel={stop} onLostPointerCapture={stop}
         onDoubleClick={onReset} />
  );
};
