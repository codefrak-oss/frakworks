---
entity: doc/observability
---

# observability

VictoriaMetrics + VictoriaLogs + Grafana for the local stack
(`compose.local.yaml`, included by the repo-root `compose.yaml`) and the
Electron desktop app (`desktop/`). The engine is the only emitter: it follows
each live sandbox's stdout into VictoriaLogs (one row per stream-json line,
with `issue`, `host`, `filename`, `phase`, `model`) and serves
`smith_run_active` and `smith_fleet_last_scrape_timestamp_seconds` on
`engine:8080/metrics`, which `vmetrics/scrape.local.yml` scrapes
(`engine/README.md`, "Transcripts and the live-run gauge"). `phase` is the
engine's run mode (`work`, `review`, `security`, ...), and `filename` is
`issue-<n>-<mode>-<runId>.log`.

Everything the Operator era added here and nothing on the engine's path fed
any more was removed on 2026-09-27: `fleet-status` (and the `/status-fleet`
skill), the Übersicht `uebersicht/fleet-data` feed and the desktop's Python
fallback to it, the `claude-usage` and `fleet-overview` dashboards, the
alerting provisioning, the `claude_usage_*` / `codex_usage_*` /
`smith_queue_*` series contract and the Alloy relabel rules. Git history has
them. Account usage now lives in the engine's database and the desktop app's
Accounts view.

## What's here

| Path | Purpose |
|---|---|
| `compose.local.yaml` | VictoriaMetrics, VictoriaLogs and Grafana on localhost, scraping themselves and the engine (`vmetrics/scrape.local.yml`). See "Run locally" |
| `grafana/provisioning/` | Datasources + dashboard provider, so dashboards are never clicked into the UI |
| `dashboards/agent-activity.json` | **Right now**: how many Smiths are running and on which host, from `smith_run_active`. Live headcount, per-host bar gauge, one-row-per-agent table, history. GitHub links are built from the `repo` label |
| `dashboards/smith-fleet.json` | Fleet run history from the Smith logs in VictoriaLogs: runs / cost / errors over 7 days, and per-ticket spend as three horizontal stacked-bar panels (one bar per ticket, segmented by phase): cost ($), content tokens (`usage.input_tokens + usage.cache_creation_input_tokens + usage.output_tokens`) and context churn (`usage.cache_read_input_tokens` alone). All three group by `child` (issue #372), so a review run's cost stacks onto the ticket it reviewed; each carries an `unphased` segment (`NOT phase:*`) so the stack reconciles with the fleet-wide stat panels; sorted by the ticket's most recent run, capped at 15 tickets. `repo` is a constant dashboard variable |
| [`desktop/`](desktop/) | **Agent Army desktop app**: an Electron app for the operator's Mac: tickets, transcripts, the engine's process instances, and proxied Claude sessions. Install, macOS packaging and tests: `desktop/README.md` |

## Run locally

`compose.local.yaml` (included by the repo-root `compose.yaml`;
`docs/local-stack.md`) runs VictoriaMetrics, VictoriaLogs
and Grafana on localhost with named volumes. Grafana is
`http://localhost:3000`, `admin` / `GRAFANA_ADMIN_PASSWORD` in the root `.env`,
with both dashboards and both datasources provisioned. It mounts
`grafana/provisioning/datasources` and `dashboards` only; there is no
alerting provisioning.

## The series the engine emits

| Metric | Labels | Meaning |
|---|---|---|
| `smith_run_active` | `host`, `issue`, `model`, `kind`, `repo` | Always `1`; the series is **absent** when that Smith is not running. Count the series for a live headcount, group by `model` / `issue` / `kind` / `repo` for the breakdown |
| `smith_fleet_last_scrape_timestamp_seconds` | `host` | Unix seconds; always present, even with zero live Smiths. A value is "stale" when this companion is missing or older than five minutes, never because the value itself is zero |

`kind` is what the run was for (`job`, `review`, `security-review`) so cost
and headcount split by purpose, not only by ticket. `repo` is
`<owner>/<name>` and is never empty. The scrape interval is 15 s, so a
finished run reads as live for at most one scrape after it leaves `/metrics`.

The run-end `result` event (with `total_cost_usd`, `usage.*`, `session_id`,
`is_error`, `num_turns`) lands in VictoriaLogs with the run's `issue`,
`phase` and `model` labels; that, not the gauge, is where cost questions are
answered.

## Known gotchas (read before extending the dashboards)

- **Dedupe on `session_id`.** A run's log can reach VictoriaLogs more than
  once, and VictoriaLogs has no delete, so every panel that sums or counts
  `result` events goes through `stats by (session_id) max(...)` before the
  outer sum/count. Keep that shape in any new panel.
- **Grafana's `victoriametrics-logs-datasource` plugin returns every query
  as a raw-log-line frame by default**, whether the LogsQL is a plain query
  or a `| stats` pipe. A stat/table panel needs `"queryType": "stats"` on the
  target; a time-bucketed aggregation (`stats by (_time:1d) ...`) needs
  `"queryType": "statsRange"`. The plugin's internal enum values are not what
  the query-editor UI calls them (its `"instant"` is the raw-logs mode;
  `"stats"` is what the UI labels "Instant"). **Verify any new panel query
  against `POST /api/ds/query` before trusting it renders**: a broken
  datasource UID or a missing `queryType` fails silently (empty panel).
- **A dashboard JSON's `datasource.uid` must match the provisioned uid.**
  `grafana/provisioning/datasources/datasources.yml` pins explicit `uid:`
  fields; reference that same string in every panel. A mismatch also fails
  silently.
- **Grouped `stats by (label) ...` results come back as one frame per
  group**, not one table. A `table` panel needs the `labelsToFields`
  transform (plus `merge` when combining more than one stats target). The
  same pair pivots several single-metric `stats by (issue) sum(x) as <name>`
  targets into the wide table a stacked `barchart` panel needs.
- **`stats sum(...)` / `avg(...)` on a raw `unpack_json` field can silently
  return `NaN`** for some legitimate high-precision floats (seen on
  `total_cost_usd`), and an un-grouped sum silently drops those rows instead
  of erroring. Always cast first: `| unpack_json | filter total_cost_usd:* |
  math total_cost_usd * 1 as cost | stats ... sum(cost)`. The `filter
  <field>:*` also drops events that never carried the field, so Grafana
  renders no value rather than a fabricated `0`.
- **A multi-field `math` expression needs every addend guarded**, not just
  the first: `filter usage.input_tokens:* AND
  usage.cache_creation_input_tokens:* AND usage.output_tokens:*` before the
  `math` pipe that adds them, or the unguarded addend feeds the `NaN` trap.
- **A phase-filtered panel silently drops runs whose events carry no
  `phase`.** The smith-fleet per-ticket panels carry an `unphased` target
  (`AND NOT phase:*`, same cast/filter treatment, its own fixed colour) so the
  stacked total reconciles with the fleet-wide stat panels; add the same to
  any new phase-segmented panel.
- **`stats ... max(_time) as <alias>` breaks the plugin's `sortBy` transform,
  silently** (issue #463). `_time` is time-typed, so the result serialises as
  an RFC3339 string and the plugin's float parse fails; Grafana shows a small
  warning triangle and the sort has nothing to sort on. Cast through `math`
  first: `| stats by (x) max(_time) as last_run | math round(last_run / 1e9)
  as last_run`. Don't swap to `max(rev)`: many tickets share a `rev`, so it
  sorts far coarser than true recency.
