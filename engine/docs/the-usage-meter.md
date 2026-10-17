# The usage meter

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

How full each account's 5-hour and 7-day allowance is, read off the Smiths'
own traffic. The Operator read the meter from the undocumented
`/api/oauth/usage` endpoint with a login's short-lived access token, refreshed
by the CLI; a `claude setup-token` is refused there, so the engine's accounts
were blind. What was found instead (probed 2026-09-23, with both a login
token and a setup-token): the setup-token is an ordinary bearer for the
Messages API, and **every Messages reply carries the meter** as headers:

```
anthropic-ratelimit-unified-5h-utilization: 0.08      0..1 of the 5-hour window
anthropic-ratelimit-unified-5h-status: allowed        allowed | allowed_warning | rejected
anthropic-ratelimit-unified-5h-reset: 1790230200      epoch seconds
anthropic-ratelimit-unified-7d-utilization: 0.17      the same three for the week
anthropic-ratelimit-unified-7d-status: allowed
anthropic-ratelimit-unified-7d-reset: 1790719200
anthropic-ratelimit-unified-status: allowed           the verdict on this request
```

A `claude-fable-*` reply also carries the **Fable limit** as its own window,
`7d_oi` (found live 2026-09-26; an Opus reply on the same account has none of
it):

```
anthropic-ratelimit-unified-7d_oi-utilization: 0.79   0..1 of the Fable limit
anthropic-ratelimit-unified-7d_oi-reset: 1790719200   epoch seconds
anthropic-ratelimit-unified-7d_oi-status: allowed_warning
anthropic-ratelimit-unified-7d_oi-surpassed-threshold: 0.75
```

The same reply had `-reset`, `-representative-claim`, `-fallback-percentage`,
`-overage-status`, `-overage-disabled-reason` and `-upgrade-paths` too; none
of those is recorded.

The numbers are the **account's**, not the request's: they include the user's
interactive sessions on that account and every other Smith on it.

So each Smith sandbox reads the meter off its own traffic:
`engine/smith/run-smith.sh` starts `engine/smith/meter-proxy.py`, a passive,
stdlib-only pass-through on `127.0.0.1` inside the sandbox, and points
`ANTHROPIC_BASE_URL` at it; Claude Code sends its requests there and the proxy
forwards them to `api.anthropic.com` exactly as sent, bearer and all,
streaming the reply back as it arrives. After every reply it writes the
seven headers above and the `7d_oi` utilization and reset to
`/work/out/meter.json` (`METER_PATH`), latest reading wins. The `7d_oi` pair
is the last one any reply in the run carried: a Fable run also makes calls on
other models, and a reply without the pair keeps it rather than nulling it:

```
{"observedAt": "2026-09-25T10:00:00Z", "status": 200,
 "anthropic-ratelimit-unified-5h-utilization": "0.08", ..., "anthropic-ratelimit-unified-status": "allowed"}
```

`status` is that reply's HTTP status; a header the reply lacked is `null`.
`collect result` in `smith-run` copies the file out with the rest of
`/work/out` into the bytes variable `meter`, and records it once per run
under the run's `account` in `engine.ai_account_usage`, one row per account,
latest reading wins. Bodies are never read, logged or stored. **The one
request of the engine's own that the meter reads** is the on-demand refresh,
`POST /accounts/{name}/usage/refresh` ("AI accounts"; the desktop's per-tile
**Refresh usage**): the credential probe, a one-token `claude-haiku-4-5`
message, whose reply's headers are recorded through the same
`AccountUsages.record` as a collected run's, latest reading wins. A Haiku
reply has no `7d_oi` pair, so a refresh keeps the account's last Fable
reading, as any non-Fable run does. A 429 reply is recorded like any other
(its `httpStatus` 429, its `status` the reply's, usually `rejected`, which the
picker then honours until the window's reset). Verifying a credential sends
the same probe and does not read the meter. Nothing refreshes on a schedule.
So without a refresh the meter is blank when idle, and "idle" covers two cases:
no Smith running on the account, and a run whose Smith made no Anthropic call
(a dry run, or one that failed before its first request), which leaves no
`meter.json` and records nothing (logged at DEBUG). That is acceptable
because utilization only rises through requests: an idle account's last
reading is a ceiling, and a picker that trusts it errs toward caution. The
gaps are interactive use of a shared account while no Smith runs on it, and
the length of a run: a reading lands when the run is collected, so the row is
a few minutes stale at worst while a Smith is working. The next collected run
corrects it, and if its last request was refused the row says so.

**A jev account has no meter.** Jev bills prepaid credit packs (1 credit =
1,000 input tokens, output free), so its usage would be a balance that only
goes down, not a window with a reset. TypeSafe exposes no balance endpoint:
as of 2026-09-26 the docs have no billing, credits or usage call, and
`/v1/credits`, `/v1/balance`, `/v1/usage`, `/v1/billing` and
`/v1/organization` all answer 404
([quota-axi#291](https://github.com/kunchenguid/quota-axi/issues/291)). So
nothing is stored: `/accounts/<jev>/usage` answers 404, `/accounts/usage` has
no jev entry, and the desktop tile says "no usage data". No window is faked.
When TypeSafe adds a free balance call, store it as a number and the time it
was read, never as a window or a reset.

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" localhost:8090/accounts/usage                 # every account's latest reading
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" localhost:8090/accounts/anthropic-1/usage     # one; 404 until a run has been collected with a reading, or a refresh
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPOST localhost:8090/accounts/anthropic-1/usage/refresh   # ask Anthropic now (anthropic accounts only)
```

**Where it lives.** One meter proxy per sandbox, on its loopback only: no
sandbox reaches the engine for it, and the engine has no `/anthropic`
endpoint. (Until #145 the proxy was an engine endpoint, `/anthropic/**`,
shared by every sandbox and told apart by bearer; that is gone, with
`engine.smith.anthropic-base-url`.) The readings go to the engine's database
only: nothing reaches VictoriaMetrics or Grafana yet (the desktop app's
Accounts view reads them from the engine). `ai_account_usage` is nearly one
row per `ai_account` row, not enforced: `engine` has no account row, an
account with no metered run yet has no usage row (its endpoint answers 404),
and a removed account keeps its last reading.

Each row: both windows' `utilization`, `status` and `reset`, the overall
`status`, the last reply's `httpStatus`, `requests` (collected runs with a
reading plus on-demand refreshes, not individual requests), `observedAt` (when the sandbox saw the
reply), and `rejected`: the last reply was refused and the later of the two
resets has not passed. The picker reads the windows' statuses and resets
instead ("AI accounts": each `rejected` window blocks until its own reset), and
the 7-day utilization with its reset to order by (an expiring weekly budget first);
`rejectedUntil` is when the picker lets the account back in (null when it is
not skipped). `fableUtilization` and `fableReset` are the `7d_oi` pair
(columns `fable_utilization`, `fable_reset`): the last one a collected run
read off a `claude-fable-*` reply. A reading without the pair (a run on any
other model, most of them) keeps the stored one; a reading at or past the
stored `fableReset` clears it, so it is null until the first Fable run, and
again once its window has reset and no Fable run has read the new one. Until
#465 every reading wrote it, so a single Opus run nulled it and the desktop
almost never had one to draw. The desktop's accounts view draws them as a
third meter (`F`) only when present. The picker gates Fable runs on them as
well as on `fableLimit`, below: an account whose `fableUtilization` is at or
above 1.0 with its `fableReset` ahead is out of Fable. A 429 or a `rejected` status is also logged at WARN with the
account's name when the run is collected.

**A session or spend limit named only in the result** is recorded in the same
row as `limitRejectedSince` and `limitRejectedReset`, so the next meter
reading does not overwrite it. On 2026-09-25 runs on anthropic-primary ended
"You've hit your org's monthly spend limit ... your session limit resets
3:10pm (UTC)". `collect result` records the account rejected when the
Smith's closing `result` event is an error naming "session limit" or
"monthly spend limit", or a failed run's `result.json` does (the same
evidence rules as the Fable limit below). The reset is the next instant
after the run at the clock time the text gives ("resets 3:10pm (UTC)"; the
parenthesised zone, else UTC); when the text gives none, the account's
5-hour reset if it is ahead, else its 7-day one. With neither, nothing is
recorded and a WARN says so: no window is invented. A later reset replaces
an earlier one, never the reverse, and a reading at or past it clears it.

**The Fable limit** is separate per-account state in the same row:
`fableLimit` (`unknown` or `reached`), `fableLimitSince` and
`fableLimitReset`. Anthropic caps `claude-fable-*` models apart from the two
windows: on 2026-09-25 two tier 8 runs on one account were refused with
"You've reached your Fable limit. Switch to another model to continue."
while its 5-hour window was at 61% and `allowed`. The `7d_oi` headers meter
that cap (`fableUtilization`, above), but only on a Fable reply, so the
recorded state still comes from the run itself: `collect result` marks the
account `reached` when the Smith's closing stream-json `result` event is an
error naming "reached your Fable limit", or, on a `claude-fable-*` run,
"out of usage credits" (case-insensitive; an assistant or tool line quoting
the phrase does not count; on 2026-10-05 anthropic-primary refused every
Fable run with "You're out of usage credits. Switch to another model to
continue." while both windows were `allowed`, and that wording is never a
session limit), when a failed run's `result.json` names it, or when
`meter.json` shows a 429 on a `claude-fable-*` run whose 5-hour and 7-day
statuses are both `allowed` (or absent), whatever the overall status: it
reads `rejected` when only the `7d_oi` quota is out. A 429 with either
window `rejected` is a window rejection, not the Fable limit. It is recorded
once, with the account's 7-day reset at that moment (a week out when there
is no reading), and never recomputed while it stands. It resets with the
account's 7-day window: the first reading whose 7-day reset is past the
recorded one, or taken after it, sets it back to `unknown` and logs that
once. The picker's gate: for a `claude-fable-*` model it skips an account
that is `reached` with its reset still ahead, or whose Fable meter reads at
or above 1.0 with `fableReset` ahead, and a pinned `account` in either state
fails naming the reset; when no anthropic account is left, `start smith`
fails as an incident with `no account can run Fable until <earliest reset>`
(the earliest `fableLimitReset` or `fableReset` among them). That message
wins whenever an account could run another model now, even if some other
account is rejected; only with none usable at all does a Fable run fail with
`no account available until <t>` (#2511). Every other model ignores the Fable limit.

The sandbox's proxy only exists once the Smith image is rebuilt
(`docker compose --profile tools build smith`); an older image makes no
`meter.json` and the meter stays blank. Claude Code honours
`ANTHROPIC_BASE_URL` under a subscription login: confirmed live 2026-09-24
with test intakes through the engine-side proxy this one replaced. A row
updating while the Smith fails is not proof the proxy is transparent: the
Smith's log is.
