// build-strip.jsx — the builds in progress, above Analytics > Build gate's
// Grafana frame (#1389). Drawing only, like grafana.jsx: index.jsx polls
// window.fleet.activeBuilds (the engine's GET /builds/active) and ticks the
// clock, and hands both over.
//
// One horizontal bar per build, oldest first: its width is the time it has
// waited against the engine's check timeout, its label `repo#n` opens the
// ticket in the app (frakworks://ticket/...), and the elapsed time sits after it.

/** "PT3H", "PT90M", "PT1H30M20S" in seconds; null for anything else. */
export const durationSecs = (iso) => {
  const m = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/i.exec(String(iso || "").trim());
  if (!m || !(m[1] || m[2] || m[3])) return null;
  return Number(m[1] || 0) * 3600 + Number(m[2] || 0) * 60 + Number(m[3] || 0);
};

/** Elapsed seconds as `m:ss`, or `h:mm:ss` from an hour on. */
export const elapsedLabel = (secs) => {
  const s = Math.max(0, Math.floor(Number(secs) || 0));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  const pad = (n) => String(n).padStart(2, "0");
  return h ? `${h}:${pad(m)}:${pad(r)}` : `${m}:${pad(r)}`;
};

/** A build's row: its label, its ticket link (null without repo and issue), elapsed seconds and bar width (percent). */
export const buildRow = (b, now, timeoutSecs) => {
  const since = Date.parse(b && b.since);
  const secs = Number.isFinite(since) ? Math.max(0, (now - since) / 1000) : 0;
  const repo = b && typeof b.repo === "string" ? b.repo : null;
  const issue = b && Number.isSafeInteger(Number(b.issue)) && Number(b.issue) > 0 ? Number(b.issue) : null;
  const name = repo ? repo.split("/").pop() : "?";
  const label = issue ? `${name}#${issue}` : repo || "unknown build";
  const href = repo && issue ? `frakworks://ticket/${repo}/${issue}` : null;
  const width = timeoutSecs > 0 ? Math.min(100, Math.max(1, (secs / timeoutSecs) * 100)) : 100;
  return { key: (b && b.processInstanceId) || label, label, href, secs, width, check: (b && b.check) || null, repo };
};

/**
 * `data` is null while the first read runs, else `{ builds, timeout }` or
 * `{ error }`; `now` is the clock in ms.
 */
export const BuildStrip = ({ data, now }) => {
  if (!data) return <div className="build-strip"><div className="meta">reading builds in progress…</div></div>;
  if (data.error) return <div className="build-strip"><div className="err">builds in progress: {data.error}</div></div>;
  const builds = Array.isArray(data.builds) ? data.builds : [];
  const timeout = durationSecs(data.timeout);
  const rows = builds.map((b) => buildRow(b, now, timeout));
  return (
    <div className="build-strip">
      <div className="build-strip-head">Builds in progress ({rows.length})</div>
      {rows.length === 0 ? <div className="meta">no builds running</div> : rows.map((r) => (
        <div className="build-row" key={r.key}>
          <span className="build-label">
            {r.href ? <a href={r.href} title={`${r.repo}#${r.label.split("#")[1]}${r.check ? `: check ${r.check}` : ""}`}>{r.label}</a> : r.label}
          </span>
          <span className="build-track"><span className="build-bar" style={{ width: `${r.width}%` }} /></span>
          <span className="build-elapsed">{elapsedLabel(r.secs)}</span>
        </div>
      ))}
    </div>
  );
};
