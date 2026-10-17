// grafana.jsx — one Grafana public dashboard under Explorer > Analytics
// (issue #332). Drawing only, like first-mate-model.jsx: no fetch, no timers,
// no `window.` reference. index.jsx asks main to probe the dashboard
// (window.fleet.grafanaProbe) and hands the answer over as `check`.
//
// The frame is shown only after the probe answered ok: a cross-origin iframe
// cannot report that its page failed to load, so without the probe a down
// Grafana would be a blank rectangle rather than the usual error block.

// What the frame may do. Scripts (Grafana is an app), its own origin (its API
// calls), and popups (a panel link), which main hands to the system browser
// only if they are http(s) (src/main.js openOutside). No top navigation, no
// forms, no downloads.
import { BuildStrip } from "./build-strip.jsx";

export const FRAME_SANDBOX = "allow-scripts allow-same-origin allow-popups";

/**
 * `dashboard` is `{ label, url, error }` from window.fleet.grafanaDashboards();
 * `check` is null while the probe runs, else `{ ok }` / `{ ok: false, error }`.
 * A dashboard with `builds` gets the builds-in-progress strip above its frame
 * (build-strip.jsx): `builds` is window.fleet.activeBuilds()'s answer, `now` the clock.
 */
export const GrafanaDashboard = ({ dashboard, check, builds = null, now = Date.now() }) => {
  const d = dashboard || {};
  const failed = !d.url ? (d.error || "this Grafana dashboard is not configured")
    : check && !check.ok ? (check.error || `Grafana did not answer at ${d.url}`) : null;
  if (failed || !check) {
    return (
      <div className="fleet-root">
        <div className="head"><h1>{d.label || "Grafana"}</h1></div>
        {failed ? <div className="err">{failed}</div> : <div className="meta">checking Grafana at {d.url}…</div>}
      </div>
    );
  }
  const frame = <iframe className="grafana" title={d.label} src={d.url} sandbox={FRAME_SANDBOX} />;
  if (!d.builds) return frame;
  return <div className="grafana-with-builds"><BuildStrip data={builds} now={now} />{frame}</div>;
};
