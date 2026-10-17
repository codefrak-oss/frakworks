// pair-phone.jsx — the web version's Pair a phone page (observability/desktop/
// README.md, "Pair a phone"; ios/README.md): a
// first-mate token minted as the signed-in login and a QR of {url, token} for
// the iPhone app to scan, plus the phone tokens with Revoke. With no public URL
// set it says so and mints nothing: a QR of an address the phone cannot reach
// pairs nothing.
import { useEffect, useState } from "react";
import { qrSvg, pairingPayload } from "../qr.js";

export const QrCode = ({ text, px = 240 }) => {
  const q = qrSvg(text);
  return (
    <svg className="pairQr" role="img" aria-label="pairing QR code" width={px} height={px}
      viewBox={`0 0 ${q.size} ${q.size}`} shapeRendering="crispEdges">
      <rect width={q.size} height={q.size} fill="#fff" />
      <path d={q.path} fill="#000" />
    </svg>
  );
};

const today = () => new Date().toISOString().slice(0, 10);

export const PairPhone = ({ pairing }) => {
  const [url, setUrl] = useState(null);
  const [minted, setMinted] = useState(null);
  const [tokens, setTokens] = useState({ tokens: [], error: null });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const reload = () => pairing.tokens().then(setTokens);
  useEffect(() => { pairing.publicUrl().then(setUrl); reload(); }, []);

  const mint = async () => {
    setBusy(true); setError(null);
    const r = await pairing.mint(`iPhone ${today()}`);
    setBusy(false);
    if (r.error) { setError(r.error); return; }
    setMinted(r);
    reload();
  };
  const revoke = async (id) => {
    const r = await pairing.revoke(id);
    if (r.error) { setError(r.error); return; }
    if (minted && minted.id === id) setMinted(null);
    reload();
  };

  return <PairView url={url} minted={minted} tokens={tokens} error={error} busy={busy} onMint={mint} onRevoke={revoke} />;
};

/** The page drawn from its state, apart from the reads (so a test draws it). */
export const PairView = ({ url, minted, tokens, error, busy, onMint, onRevoke }) => (
    <div className="pairPhone">
      <h1>Pair a phone</h1>
      {!url ? <p>Reading the engine's public URL…</p>
        : url.error ? <p className="error">{url.error}</p>
        : !url.publicUrl ? (
          <p className="pairUnset">The engine has no public URL set, so there is nothing a phone could reach: no QR is shown and no
            token is minted. {url.note || "Set it with PUT /settings/public-url."}</p>
        ) : (
          <>
            <p>Engine: <code>{url.publicUrl}</code></p>
            {minted ? (
              <div className="pairMinted">
                <QrCode text={pairingPayload(url.publicUrl, minted.token)} />
                <p>Scan this in the iPhone app. The token <code>{minted.token}</code> is shown once, here only: it is
                  listed below by its name and can be revoked like any other.</p>
              </div>
            ) : <button type="button" disabled={busy} onClick={onMint}>{busy ? "Minting…" : "Mint a phone token"}</button>}
          </>
        )}
      {error ? <p className="error">{error}</p> : null}
      <h2>Phone tokens</h2>
      {tokens.error ? <p className="error">{tokens.error}</p> : null}
      {tokens.tokens.length === 0 ? <p>None.</p> : (
        <ul className="pairTokens">
          {tokens.tokens.map((t) => (
            <li key={t.id}><span>{t.name}</span> <code>{t.prefix}…</code> {t.login}
              {" "}<button type="button" onClick={() => onRevoke(t.id)}>Revoke</button></li>
          ))}
        </ul>
      )}
      <p><a href="#/">Back to the app</a></p>
    </div>
);
