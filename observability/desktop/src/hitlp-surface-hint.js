// hitlp-surface-hint.js — Config > frakworks > HITLP server's hint (#2487),
// apart from src/hitlp-surface.js so the renderer bundle takes no main-only code.
"use strict";

const SURFACE_HINT = "The HITLP server's surface API (its base URL ends /surface/v1) and your surface credential. HITLP Requests and the urgent popup are read and decided there, as you. Checked with GET /tasks before it is kept; kept encrypted with safeStorage, and the window never sees it.";
/** The web version's (#2489): the page itself keeps both, for the tab's life. */
const SURFACE_HINT_WEB = "The HITLP server's surface API (its base URL ends /surface/v1) and your surface credential. HITLP Requests and the urgent popup are read and decided there, as you, straight from this page (the server must allow this page's origin). Checked with GET /tasks before it is kept; kept in this tab's session storage until Clear, sign-out or the tab closes.";

module.exports = { SURFACE_HINT, SURFACE_HINT_WEB };
