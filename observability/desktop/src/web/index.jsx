// index.jsx — the web version's entry (observability/desktop/README.md, "Web
// version"): the desktop's renderer (src/renderer/index.jsx), the same
// module, mounted behind a sign-in screen. Nothing of the engine is shown, and
// no engine read is made, before a token the engine accepts (GET /whoami) is in
// this tab; a sign-out, from the gate or from Config > frakworks > Sign in,
// reloads the page back to the gate.
import { bridge } from "./install.js";
import { useState } from "react";
import { createRoot } from "react-dom/client";
import { mount } from "../renderer/index.jsx";
import { SignInPanel } from "../renderer/sign-in.jsx";
import { PairPhone } from "../renderer/pair-phone.jsx";

const { fleet } = bridge;
const signOut = fleet.signOut;
fleet.signOut = async () => {
  const r = await signOut();
  window.location.reload();
  return r;
};

const Gate = ({ first }) => {
  const [status, setStatus] = useState(first);
  return (
    <div className="webGate">
      <h1>frakworks</h1>
      <SignInPanel web result={status} onRefresh={() => fleet.signInStatus().then(setStatus)}
        onSignIn={async (token, opts) => {
          const r = await fleet.signIn(token, opts);
          if (!r.error) window.location.reload();
          return r;
        }} onSignOut={() => fleet.signOut()} />
    </div>
  );
};

// `#/pair`, Pair a phone (README, "Pair a phone"): a page of its own, signed in
// like the rest; the app carries a link to it, and leaving it reloads the app.
const PAIR_HASH = "#/pair";
const root = document.getElementById("web-root");
fleet.signInStatus().then((s) => {
  if (!s.signedIn || s.error) { createRoot(root).render(<Gate first={s} />); return; }
  if (window.location.hash === PAIR_HASH) {
    createRoot(root).render(<PairPhone pairing={bridge.pairing} />);
    window.addEventListener("hashchange", () => { if (window.location.hash !== PAIR_HASH) window.location.reload(); });
    return;
  }
  mount(root);
  const link = document.createElement("a");
  link.href = PAIR_HASH;
  link.className = "webPairLink";
  link.textContent = "Pair a phone";
  link.addEventListener("click", (ev) => { ev.preventDefault(); window.location.hash = PAIR_HASH; window.location.reload(); });
  document.body.appendChild(link);
});
