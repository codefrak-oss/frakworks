// palettes.jsx — Config > Palettes (#2213): the dark colour schemes,
// and a picker that applies one. A
// scheme is the ten role colours styles.css reads from :root (--bg, --surface,
// --border, --text, --muted, --accent, --ok, --warn, --err, --info); the choice
// is kept in localStorage and set on <html> as inline custom properties.
import React, { useState } from "react";

export const ROLES = ["bg", "surface", "border", "text", "muted", "accent", "ok", "warn", "err", "info"];

// The proposal's tables, role order as ROLES. "current" is the app's palette before #2213.
const scheme = (id, name, mood, hex) => ({ id, name, mood, colors: Object.fromEntries(ROLES.map((r, i) => [r, hex[i]])) });
export const PALETTES = [
  scheme("current", "Current", "Cool slate gray, white text, a blue-only severity ramp.",
    ["#14161b", "#1b1e24", "#25272c", "#e6e8eb", "#9aa0a8", "#4c86e0", "#4c86e0", "#8fb8f2", "#e3eefb", "#4c86e0"]),
  scheme("ember", "A. Ember", "Charcoal by a fireplace: warm off-white text, amber accent.",
    ["#141210", "#1d1a17", "#2e2924", "#f2e6d8", "#a8998a", "#f0a24a", "#8fcf7a", "#f2c14e", "#ff7a66", "#7fb2e5"]),
  scheme("aurora", "B. Aurora", "Night sky over snow: icy mint text, teal-green and violet glows.",
    ["#0f1417", "#161d21", "#243035", "#dff5ef", "#8fa8a3", "#3fd6b0", "#5be08a", "#e8c35a", "#ff7b8a", "#a99cff"]),
  scheme("synthwave", "C. Synthwave", "Neon on black asphalt: lavender text, hot magenta, cyan info.",
    ["#121018", "#1a1722", "#2c2638", "#ece4ff", "#a598c0", "#ff5cc8", "#4fe0a0", "#ffcf5c", "#ff6b7f", "#4fd8ff"]),
  scheme("moss", "D. Moss & Stone", "Forest floor at dusk: sage-tinted text, moss accent, earthy statuses.",
    ["#121411", "#1a1d18", "#2a2f27", "#e3ead9", "#99a38e", "#9ccc65", "#7cc47a", "#d9b44a", "#e5806b", "#84b0c9"]),
  scheme("phosphor", "E. Phosphor", "Old amber terminal: warm gray glass, gold text glow, retro readouts.",
    ["#111111", "#191917", "#2b2a26", "#f5deb0", "#a99f8a", "#ffb000", "#a8d86a", "#ffd75e", "#ff7059", "#8cc4e8"]),
  scheme("deep-sea", "F. Deep Sea", "Bioluminescence in a trench: pale aqua text, electric blue accent.",
    ["#0d1218", "#141b24", "#22303e", "#d6ecff", "#8aa2b8", "#3aa8ff", "#4fd1a1", "#f5c76b", "#ff7f8e", "#7fdcff"]),
  scheme("rose-noir", "G. Rosé Noir", "Velvet lounge: blush text on soft black, rose accent, gentle statuses.",
    ["#151214", "#1e1a1d", "#30292e", "#f5e1e8", "#a8949d", "#ff8fb1", "#8ad6a6", "#f0c27a", "#ff6f7a", "#9ab6f0"]),
  scheme("copper", "H. Copper Circuit", "Machine room: neutral graphite, copper accent, verdigris success.",
    ["#131415", "#1b1c1e", "#2c2e31", "#ece7e2", "#9d9a96", "#e08a50", "#5fc9a8", "#e8bd55", "#f2766a", "#7aa8e8"]),
];

export const PALETTE_KEY = "palette";
export const paletteById = (id) => PALETTES.find((p) => p.id === id) || PALETTES[0];
export const loadPaletteId = () => { try { return paletteById(localStorage.getItem(PALETTE_KEY)).id; } catch (e) { return PALETTES[0].id; } };

/** Sets the palette's roles on `root` (document.documentElement) and remembers the choice. */
export const applyPalette = (id, root = typeof document !== "undefined" ? document.documentElement : null) => {
  const p = paletteById(id);
  if (root) for (const r of ROLES) root.style.setProperty(`--${r}`, p.colors[r]);
  try { localStorage.setItem(PALETTE_KEY, p.id); } catch (e) { /* private window etc. */ }
  return p.id;
};

const Sample = ({ p }) => {
  const c = p.colors;
  return (
    <div className="palSample" style={{ background: c.bg, borderColor: c.border, color: c.text }}>
      <div style={{ background: c.surface, borderRadius: 6, padding: "6px 8px" }}>
        <div>Ticket #2213 <span style={{ color: c.accent }}>accent</span></div>
        <div style={{ color: c.muted, fontSize: 11 }}>muted meta text</div>
        <div className="palDots">
          {["ok", "warn", "err", "info"].map((r) => <span key={r} style={{ color: c[r] }}>● {r}</span>)}
        </div>
      </div>
    </div>
  );
};

/** The picker: one card per scheme; clicking one applies it at once. */
export const PalettesView = ({ initial = loadPaletteId(), onApply = applyPalette }) => {
  const [chosen, setChosen] = useState(initial);
  const pick = (id) => { setChosen(onApply(id) || id); };
  return (
    <div className="palettes">
      <div className="head"><h1>Palettes</h1></div>
      <div className="meta">Pick the app's colour scheme; it applies at once and is remembered on this machine.</div>
      <div className="palGrid">
        {PALETTES.map((p) => (
          <button key={p.id} className={`palCard${p.id === chosen ? " on" : ""}`} aria-pressed={p.id === chosen}
            onClick={() => pick(p.id)}>
            <div className="palName">{p.name}{p.id === chosen ? " ✓" : ""}</div>
            <div className="palMood">{p.mood}</div>
            <Sample p={p} />
          </button>
        ))}
      </div>
    </div>
  );
};
