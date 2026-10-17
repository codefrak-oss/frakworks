// design-deck.jsx — a confirm-mode feature's design proposal as a dark slide
// deck (#100114; engine/docs/feature.md, "The design deck"). The engine
// templates the page from the design result (`GET /ticket/<owner>/<repo>/<n>/design-deck`,
// through `fleet.designDeck`); it is drawn like a pitch page, in the
// sandboxed srcdoc frame with its own CSP (src/pitch-frame.js): nothing in it
// runs or reaches the network. The answer path stays the decision card's.

import { useState, useEffect } from "react";
import { TicketPitchView } from "./pitch.jsx";

export const DECK_HEADING = "Proposal";

/** The deck of `repo` ("owner/name") `number`: resolves to the page's html, or null for none; never rejects. */
export const loadDesignDeck = ({ repo, number, fleet }) => {
  const [owner, name] = String(repo || "").split("/");
  if (!(fleet && fleet.designDeck && owner && name && number)) return Promise.resolve(null);
  return Promise.resolve().then(() => fleet.designDeck(owner, name, Number(number)))
    .then((r) => (r && typeof r.html === "string" && r.html.trim() ? r.html : null), () => null);
};

const useDeck = (repo, number, fleet) => {
  const [html, setHtml] = useState(null);
  useEffect(() => {
    setHtml(null);
    let live = true;
    loadDesignDeck({ repo, number, fleet }).then((h) => { if (live) setHtml(h); });
    return () => { live = false; };
  }, [repo, number]);
  return html;
};

const useEscape = (full, close) => useEffect(() => {
  if (!full) return undefined;
  const onKey = (e) => { if (e.key === "Escape") close(); };
  window.addEventListener("keydown", onKey);
  return () => window.removeEventListener("keydown", onKey);
}, [full]);

/** The ticket detail's Proposal section: the deck, foldable and full-window; nothing for a ticket with none. */
export const DesignDeck = ({ repo, issue, fleet = typeof window !== "undefined" ? window.fleet : null }) => {
  const html = useDeck(repo, issue, fleet);
  const [full, setFull] = useState(false);
  const [open, setOpen] = useState(true);
  useEscape(full, () => setFull(false));
  return <TicketPitchView html={html} heading={DECK_HEADING} full={full} onOpen={() => setFull(true)}
                          onClose={() => setFull(false)} open={open} onFlip={() => setOpen((o) => !o)} />;
};

/** The decision card's "Open presentation": shown only when the ticket has a deck, which opens over the whole window. */
export const DesignDeckLink = ({ repo, number, fleet = typeof window !== "undefined" ? window.fleet : null }) => {
  const html = useDeck(repo, number, fleet);
  const [full, setFull] = useState(false);
  useEscape(full, () => setFull(false));
  if (!html) return null;
  return (
    <div className="deckLink">
      <button type="button" className="pitchOpen" onClick={() => setFull(true)}>Open presentation ⤢</button>
      {full && <TicketPitchView html={html} heading={DECK_HEADING} full open={false} onClose={() => setFull(false)} />}
    </div>
  );
};
