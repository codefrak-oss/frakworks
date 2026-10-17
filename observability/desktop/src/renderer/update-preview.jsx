// update-preview.jsx — the update preview modal (#100091): before a
// downloaded desktop update installs (Restart) and before the engine updates
// (the lit Update engine), one modal says what the new version brings: the
// version pair, one row per ticket (`#n` as a ticket link, then its title
// from the engine's work-item store, or "title unavailable"), a `PR #n` row
// for a PR naming no ticket, the one amber line when the list is missing,
// and Cancel and Update. Drawing only: the model is src/update-preview.js's
// `previewModel`, built in main. The mockups are docs/mockups/update-preview/.
//
// The app behind it is blurred without #1110's per-frame cost: in Electron,
// `snapshot` is one capture of the window (`app:snapshot`), painted under
// the scrim and blurred once; with none (the web version), the scrim takes
// `backdrop-filter`, as the HITLP popup does (#2196). Escape cancels; focus
// starts on Update, so Enter updates.

import { ModalOverlay } from "./modal-overlay.jsx";

const Row = ({ row, onOpenTicket }) => {
  if (row.kind === "pr") {
    return (
      <li className="upRow">
        <a className="upPr" href={row.url}>PR #{row.pr}</a>
        <span className="upTitle">{row.title}</span>
      </li>
    );
  }
  return (
    <li className="upRow">
      <a className="upNum" href={row.url}
         onClick={(e) => { if (onOpenTicket) { e.preventDefault(); const m = /^frakworks:\/\/ticket\/([^/]+\/[^/]+)\/(\d+)$/.exec(row.url || ""); if (m) onOpenTicket(m[1], Number(m[2])); } }}>#{row.n}</a>
      {row.title ? <span className="upTitle">{row.title}</span> : <span className="upTitle upUnknown">title unavailable</span>}
    </li>
  );
};

export const UpdatePreview = ({ preview, snapshot = null, onUpdate, onCancel, onOpenTicket }) => {
  if (!preview) return null;
  const h = preview.header;
  const behind = snapshot
    ? <><div className="upSnapshot" style={{ backgroundImage: `url(${snapshot})` }} aria-hidden="true" /><div className="upScrim" aria-hidden="true" /></>
    : null;
  return (
    <ModalOverlay label={preview.title} onEscape={onCancel} className="upPanel"
                  backdropClassName={snapshot ? "upSnap" : "upBlur"} behind={behind}>
      <div className="uoTitle">{preview.title}</div>
      <div className="upHeader">
        <span className="upLabel">{h.runningLabel}</span> <span className="upVer">{h.running}</span>
        <span className="upArrow"> → </span>
        <span className="upLabel">{h.incomingLabel}</span> <span className="upVer">{h.incoming}</span>
        {h.behind && <span className="upLabel"> {h.behind}</span>}
      </div>
      {preview.lines.length > 0 && <div className="upLines">{preview.lines.map((l) => <div key={l}>{l}</div>)}</div>}
      {preview.missing && (
        <div className="upMissing" role="status">
          <div className="upMissingWhy">{preview.missing}</div>
          <div>You can still update; the list is only information.</div>
        </div>
      )}
      {preview.empty && <div className="upEmpty">{preview.empty}</div>}
      {preview.rows.length > 0 && (
        <>
          <div className="upBrings">What this update brings · {preview.count} change{preview.count === 1 ? "" : "s"}</div>
          <ul className="upRows">
            {preview.rows.map((r) => <Row key={r.kind === "pr" ? `pr${r.pr}` : `t${r.n}`} row={r} onOpenTicket={onOpenTicket} />)}
            {preview.more > 0 && <li className="upRow upMore">… and {preview.more} more</li>}
          </ul>
        </>
      )}
      <div className="upFooter">{preview.footer}</div>
      <div className="upActions">
        <span className="upHint">Enter: Update · Esc: Cancel</span>
        <button className="upCancel" onClick={onCancel}>Cancel</button>
        <button className="upUpdate" autoFocus onClick={onUpdate}>Update</button>
      </div>
    </ModalOverlay>
  );
};
