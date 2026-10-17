// install.js — puts the web version's bridges on `window` (src/web/fleet-web.js)
// before the shared renderer is evaluated: src/web/index.jsx imports this
// first, and an ES module's imports run in order.
import { createWebFleet } from "./fleet-web.js";

export const bridge = createWebFleet({ storage: window.sessionStorage, prefs: window.localStorage, persist: window.localStorage, win: window });
window.fleet = bridge.fleet;
window.repo = bridge.repo;
window.app = bridge.app;
// Offering window.firstMate is what makes src/renderer/index.jsx draw the strip (#100104).
window.firstMate = bridge.firstMate;
