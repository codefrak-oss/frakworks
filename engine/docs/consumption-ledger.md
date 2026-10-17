# Consumption ledger

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

`engine.claude_usage` (V66, #1934; `accounts/ClaudeUsageLedger.kt`) keeps one
row per Claude consumer event, durably, so the desktop's Analytics > AI
Accounts > Account consumption shows the same figures after an engine restart.
Each row: `caller`, `role`, `run_id`, `repo`, `issue`, `account`, `model`,
`started`, `ended`, `duration_ms`, `cost_usd` and four token columns
(`input_tokens`, `output_tokens`, `cache_read_tokens`, `cache_write_tokens`:
an Anthropic `usage` block's `input_tokens`, `output_tokens`,
`cache_read_input_tokens`, `cache_creation_input_tokens`). A figure the engine
does not know is null, never 0.

Who writes it:

- **Every Smith run**, at `collect result` (`SmithRunRecords.recordUsage`),
  beside its `smith_run_stats` row: cost from the closing stream-json
  `result`'s `total_cost_usd`, duration from its `duration_ms` (else the run's
  start to its collect), tokens from the run's `LiveTally` (else the tally of
  the stream-json lines of its log). A run under a `doctor`, `refactor-scan` or
  `job` root flow is filed under that caller; every other run is caller
  `smith-run`, with its mode's timeline role as `role` (`work` → `coding`,
  `review` → `correctness`, any other mode its own name, as "Ticket timeline"
  names it). The retro turn runs inside the run and is counted with it.
- **The engine's own calls**, each with its model, wall clock and the reply's
  `usage` block (nothing else of the body is read): the session summarizer
  (`summarizer`, "Summarize: a live run summarized"), the probe of
  `POST /accounts/{name}/usage/refresh` (`usage-probe`) and the Anthropic
  credential check (`credential-verify`). They have no cost: the engine has no
  price table (#1009 chose cost at the end over an estimate priced by one), so
  their `cost_usd` is null and the page shows it as unknown. OpenAI and Jev
  calls are not Claude's and are not recorded.

`GET /accounts/consumption` sums the rows that ended in the window: one row
per role of the `smith-run` caller (sorted by name), then one per other
caller, then `total`. A column is null when no row in it knew the figure, and
`unknown` counts, per column, the rows that did not (absent when all did).

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" "localhost:8090/accounts/consumption?window=1d"
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" "localhost:8090/accounts/consumption?since=2026-10-01T00:00:00Z&until=2026-10-02T00:00:00Z"
```
