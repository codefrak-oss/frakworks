# AI accounts

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

`engine.ai_account` is where an installation keeps its AI accounts:
`name`, `provider` (`anthropic`, `openai`, `jev` or `codex`), `enabled`, and
any account credential. Anthropic and OpenAI accounts can be selected for
Smith runs; Jev is used by the engine's decision calls. A `codex` account can
store the ChatGPT-mode Codex CLI `auth.json` login state, imported from the
desktop and shape-checked locally; verification and Smith execution are not
connected yet. The original JSON is stored as the account credential and is
never returned by the API. Credentials are stored **sealed**: AES-256-GCM under
`ENGINE_SECRETS_KEY` (32 bytes, base64; `openssl rand -base64 32`), with the
row's name as associated data, so a backup, `psql`, or a ciphertext copied to
another row gives nothing. That key is the one credential the deployment
configures; every account is added through the API and never needs a redeploy.
`key_id` records which key sealed each row, for rotation. Lose the key and
every credential has to be set again.

The engine is necessarily a trusted holder: for a Smith provider, at spawn it
opens the credential and hands it to the sandbox as the provider's env var (`CLAUDE_CODE_OAUTH_TOKEN`
or `OPENAI_API_KEY`) along with `SMITH_ACCOUNT=<name>`. Sealing protects the
database, not the engine; nothing can protect the engine from itself. The
secrets broker (`bots/secrets-mcp`, removed 2026-09-27) answered a different
question — what a running Smith may fetch for its ticket — and was never on
this path.

**API keys and credit** (#100142). A Max plan includes a monthly grant of
Claude API credit (e.g. $100 on Max 5x) that renews each billing cycle,
expires at its end and does not cover Claude Code. An `anthropic` account can
hold, beside its OAuth credential, an **API key** and an **Admin API key**,
both sealed like the credential (columns `api_key` and `admin_key`, each with
its sealing key's id; the associated data is `<name>/<column>`, so a key opens
only in its own row and column; a rename reseals them), and a **billing-cycle
date** and **grant** (`billing_cycle_date`, `credit_grant_cents`). They are set
through the API (or the desktop's Edit… > API keys tab) and never read back:

| Request | |
|---|---|
| `PUT /accounts/{name}/api-key` `{"key"}` | Takes `sk-ant-api03-…` or `sk-ant-usr-…`; tried first with a one-token `POST /v1/messages` on `x-api-key`. |
| `PUT /accounts/{name}/admin-key` `{"key"}` | Takes `sk-ant-admin01-…` or `sk-ant-usr-…`; tried first with `GET /v1/organizations/me`. 409 while the account has no billing-cycle date. The credit is read at once. |
| `DELETE /accounts/{name}/api-key`, `…/admin-key` | Deletes the key; clearing the admin key drops the credit figure. |
| `PUT /accounts/{name}/billing` `{"cycleDate": "YYYY-MM-DD", "grantCents"}` | The date's day of the month is when each cycle starts; the grant is per cycle, in cents. |
| `POST /accounts/{name}/credit/refresh` | Reads the credit now; 502 with the error when it fails. |

A key of the wrong shape is a 400, a key Anthropic refuses a 422 with
Anthropic's own message, and no answer at all a 502; nothing is stored in any
of them. Only an `anthropic` account takes these (409 otherwise). The admin
key must belong to a team organization: an individual org's key gets
`permission_error` on the admin endpoints.

**Credit used this cycle.** Anthropic has no balance endpoint, so the engine
sums spend itself: every 5 minutes (`ApiCreditRefresh`, under the engine
lease; cost_report allows one call a minute and lags about 5 minutes), for
each account with an admin key and a date, it pages
`GET /v1/organizations/cost_report?starting_at=<cycle start>&bucket_width=1d`
and adds every result's `amount` (cents, a decimal string) exactly. The cycle
start is the date's day in the current month (UTC), clamped to the month's
length (a 31st starts on Feb 28 or 29), or the month before's when that is
still ahead. The sum is stored in `credit_used_cents` with
`credit_refreshed_at`. A failed read keeps the last figure and stores the
reason in `credit_error`, cleared by the next good one. Each refresh publishes
the `setting` `accounts` frame, so the desktop redraws without polling.
`GET /accounts` carries `apiKeySet`, `adminKeySet`, `billingCycleDate`,
`grantCents`, `cycleStart`, `cycleEnd`, `usedCents`, `remainingCents` (grant
less used), `creditRefreshedAt` and `creditError`; never a key. The report is
the whole organization's spend: it counts what any key of the org spent, which
is the figure the grant is drawn down by.

**No claude.ai connectors in a Smith.** A `claude setup-token` token carries
inference scope only, so `claude -p` under it never loads the account's
claude.ai connectors (Drive, Gmail, Claude Docs…); only MCP servers configured
locally in the sandbox load. A keychain login (`/login`) loads them in `-p` as
well as interactively, except those still `needs-auth`. Probed 2026-09-24 with
`engine/scripts/probe-connectors.sh` (the `init` event of `claude -p
--output-format stream-json`): login → local and `claudeai` servers;
setup-token → local only. A Smith that needs a service gets it as a local MCP
server with its own credential. The one it has, the frakworks server, needs no credential:
see "The frakworks server in a Smith".

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" localhost:8090/accounts                               # list: never a credential, only hasCredential
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPOST localhost:8090/accounts -H 'content-type: application/json' -d '{"name":"anthropic-1","provider":"anthropic","email":"me@example.com"}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPUT  localhost:8090/accounts/anthropic-1/email      -H 'content-type: application/json' -d '{"email":"me@example.com"}'   # "" or null clears it
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPUT  localhost:8090/accounts/anthropic-1/credential -H 'content-type: application/json' -d '{"token":"sk-ant-oat01-..."}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPUT  localhost:8090/accounts/anthropic-1/enabled    -H 'content-type: application/json' -d '{"enabled":false}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPUT  localhost:8090/accounts/anthropic-1/name       -H 'content-type: application/json' -d '{"name":"anthropic-1-frank"}'   # rename
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XDELETE localhost:8090/accounts/anthropic-1
```

An account may carry an optional `email` (`V38`): a free-text note, for the
user's reference only, of whose login it is, since a setup-token does not
say. The engine never uses it: not to log in, pick, verify or run anything.
`POST /accounts` takes it in its body (omitted or empty is none) and `PUT
/accounts/{name}/email` sets it (`""` or `null` clears it; 404 for no such
account), each followed by a `setting` `accounts` frame. It is trimmed and
otherwise stored as typed, up to 200 characters (400 above).

`PUT /accounts/{name}/name` renames an account (`204`, then one `setting`
`accounts` frame whose `value` is the new name; the same name is a no-op
`204`). The new name follows the `POST /accounts` rule (lowercase letters,
digits, `_ . -`, at most 100 characters; `400` otherwise). It answers `404`
for no such account, and `409` when the new name is taken or while a Smith
run is live on the account (its sandbox carries `SMITH_ACCOUNT` and its flow
`billedAccount` under the old name), and for `engine` (the engine's own
token's usage name). A usage reading a removed account left under the new
name belongs to no account and is replaced by the renamed one's. In one transaction, the rename carries
over the account's row (provider, email, enabled, added, the credential,
resealed under the new name), its usage reading (`engine.ai_account_usage`,
the Fable limit included), its `engine.smith_run_stats` rows and its
`engine.jev_call` rows; a refusal changes nothing.

Each row of `GET /accounts` is `name`, `provider`, `email` (or `null`), `enabled`,
`hasCredential`, `credentialSetAt`, `addedAt`, and `running`: the Smith runs
live on that account now, per model, `[{"model": "claude-opus-5-5", "count":
2}, {"model": "claude-sonnet-5", "count": 1}]` (the model id as passed to
`claude --model`; count descending, then model), `[]` when none is. It is
read from the live-run gauge's runs (`SmithTelemetry.running()`, "Transcripts
and the live-run gauge"), in memory, so it needs no table: a run counts from
`start smith` until its sandbox exits or `collect result`, a dry run (no
account) counts nowhere, and a run resumed after an engine restart counts
under the `billedAccount` its flow recorded. It lives on `GET /accounts`
rather than an endpoint of its own so the desktop's AI Accounts tiles get it
on the poll they already make. With `/metrics` off, no VictoriaLogs URL and
no session store nothing is followed, and `running` is always `[]`.

A credential is checked before it is stored (`credentialProblem`), and a bad
one is refused with 400 saying why, keeping the one there was: no
whitespace anywhere in it, and the provider's prefix (`sk-ant-oat01-` for
Anthropic, the `claude setup-token` token and not an API key; `sk-` for
OpenAI). Whitespace inside a token is nearly always a copy across a
terminal's line wrap, and the provider would only answer it with a 401 at
the next Smith run. The desktop app checks the same rules as it is typed
(`observability/desktop/src/credential-rules.js`). For Codex, the desktop
imports `auth.json` as a file (maximum 64 KiB); the engine checks that it is
ChatGPT-mode CLI state with non-empty access, refresh, identity and account
fields, then seals the original JSON unchanged. This is structural validation,
not a live login check: `verify` remains unavailable until Codex Smith execution
is connected.

Then it is **tried against the provider** before it is stored
(`HttpCredentialVerifier`): for Anthropic a one-token message on the cheapest
model, sent as Claude Code sends it with an OAuth token; for OpenAI the model
list. A 401 or 403 is refused with 400 and the provider's own reason; a 429
is accepted (the token is good, the plan is busy). No answer, or any other
status, says nothing about the token: 502, nothing saved, unless the request
says `"verify": false`. `POST /accounts/{name}/verify` tries the stored
credential now and answers `{"valid", "reachable", "detail"}`.

`POST /accounts/{name}/usage/refresh` re-reads an Anthropic account's meter
now: it sends the same probe on the stored credential (one request builder
for both, `HttpCredentialVerifier`) and records the reply's meter headers
("The usage meter"), answering the new reading as `GET
/accounts/{name}/usage` would. `409` for a `jev` or `openai` account (no
meter), `404` for no such account or one with no credential, `502` when
Anthropic cannot be reached or answers with no `anthropic-ratelimit-unified-*`
header; none of those records anything.

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPOST localhost:8090/accounts/anthropic-1/verify
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPOST localhost:8090/accounts/anthropic-1/usage/refresh
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPUT  localhost:8090/accounts/anthropic-1/credential -H 'content-type: application/json' -d '{"token":"sk-ant-oat01-...","verify":false}'
```

**First mate's account.** First mate is a `claude -p` child of the desktop
app on the host, not a sandbox the engine starts, so the engine cannot put a
token in its environment. `POST /accounts/pick` (`AccountPickController`) is
how it gets one: the body names the model the child is about to start on (a
full id, or a tier map short name such as `fable`), and the answer is
the account the picker gives a Smith on that model, marked picked so First
mate takes its turn in the rotation, with the env var and the token in the
clear:

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPOST localhost:8090/accounts/pick -H 'content-type: application/json' -d '{"model":"fable"}'
# {"account":"anthropic-primary","provider":"anthropic","env":"CLAUDE_CODE_OAUTH_TOKEN","token":"sk-ant-oat01-..."}
```

This is the one place a Smith provider's credential leaves the engine over
HTTP; every other endpoint shows only `hasCredential`. It is `403` to a Smith
sandbox, known by its source address as in "The frakworks server in a Smith",
`503` when Docker cannot say whether the caller is one or when no account of
the provider is usable now (all rejected, or at the Fable limit for a Fable
model), and `400` for a model no provider serves. Like the rest of the API it
is unauthenticated and published on the host's loopback only: anyone who can
call it there can already replace or delete every credential. The desktop
asks once per child start (`observability/desktop/README.md`, "First mate's
account"). First mate's calls are not metered by the engine: the meter is the
account's own, so the next Smith reply on that account carries what First
mate used.

**Jev accounts.** `jev` is TypeSafe's Jev (launched 2026-09-15), a
decision-only model: application state plus a typed question in, a pick,
scores and a confidence out. A jev account is added, sealed, verified,
enabled and disabled like the others, but **no Smith ever runs on it**
(`SMITH_PROVIDERS` in `Providers.kt`): the picker only chooses among the
model's provider, and `providerOf` gives `anthropic` or `openai`, never
`jev`; `pick` and `blockedUntil` answer null for `jev` even when a jev
account is the only usable one; a run whose pinned `account` is a jev account
fails at "start smith" with "account <x> is a jev account; no Smith runs on
it"; and there is no env var for it (`credentialEnv("jev")` throws). Since no
run bills to one, no meter reading, Fable limit or limit rejection is ever
recorded for it.

**The engine's own Jev calls.** A jev key is opened only engine-side, by
`AiAccounts.jevCredential()`: the enabled, credentialed jev account least
recently used (then by name), marked used. No Smith start path reads it.
The client is `HttpJevDecider` (`com.codefrak.engine.jev`, behind the
`JevDecider` interface, which tests fake): `POST
{engine.jev.upstream}/v1/systemone` with the model pinned to `jev-1.13.0`
(`engine.jev.model`; `jev-latest` would move under the engine), the state,
and TypeSafe's typed questions (`noul`, `choice`, `score`, all three parsed).
Each attempt times out after `engine.jev.timeout-ms` (5 s); a 429 or 529 is
tried again after `engine.jev.backoff-ms` (500 ms), doubling, up to
`engine.jev.attempts` (3) in all. Any other status, a timeout, an unreadable
body or no usable jev account is **no answer**, and every use keeps a rule
that decides without Jev. Every call, answered or not, writes one
`engine.jev_call` row (`JevCalls`): its id and time, the use case, process
instance, repo and ticket, the account, the model that answered, the full
request as sent and the response as received (null on failure, with the
reason in `error`), the latency, the threshold in force, the `action` the
flow took with the pick, and a nullable `outcome` the flow fills in once it
learns whether the pick was right. Never the key. The uses today are the
feature's acceptance answer, on instances started before #583 only
("Feature", "Instances started before #583"), a proposal's
decision ("Proposal", `read the decision`), the triage of a failed engine
check ("Chore", `wait for the engine check`), a refined ticket's kind,
feature mode and tier ("Intake", `read result`), the pre-triage of an
incident the user pressed Fix on ("Fix and Propose a fix"), the intake's duplicate
check ("Intake"), the doctor's look at a new sighting ("Doctor") and the grouping
of a friction note with a new key ("Friction notes", use case `friction-group`).

**Jev decisions** (#553). `JevController` (`com.codefrak.engine.jev`) serves
the audit read-only, for the desktop's Explorer > Analytics > Jev decisions
page and the ticket detail's Jev section: `GET /jev/calls`, `GET
/jev/calls/{id}` and `GET /jev/summary` (the table at the top). It makes no
Jev call and no GitHub call, and never answers a row's `account`. The pick
is read back from the stored response (`JevAnswers`): a choice's option, a
score's level (`tier <n>` for refine-pick), a noul's `yes` (0.5 or above) or
`no` with the noul itself as its confidence, the value the acceptance
threshold is held against. The summary is computed in Kotlin over the
window's rows, so H2 and Postgres answer alike. **Agreement** (`JevAgreement`)
is the share of calls with a verdict whose outcome bore the pick out, read
from what each writer already records: acceptance `confirmed accepted` with a
`yes`, `not accepted` with a `no`; ci-triage `the re-run passed` with
`infrastructure` or `flaky`, `the re-run failed too` with `code`;
incident-triage `retried without a Smith: retried` with no `tripped again`
with `retry` (any other end of that retry against it), and the Fix Smith's
remedy when it is the pick; intake-duplicate `cancelled at the duplicate
question` with a ticket, `not cancelled` with `none`; proposal-decision `verb
as written` unless it says `(jev picked …)`, and a re-ask read as the pick;
refine-pick the `started as` prefix against the pick ("Intake");
doctor-sighting the pick against the bug the outcome shows was right
("Doctor", "Jev, for a new signature"); friction-group the pick against the
group the outcome shows was right ("Friction notes": the group a join was kept
on or a founded group was merged into, `new` for a founded group kept or a
note pulled out). A free-text
outcome (the work Smith's answer to a CI finding) counts in `withOutcome` but
is not judged. **Runs saved** are ci-triage re-runs that then passed and
incident-triage retries without a Smith that did not trip again. V24 indexes
`(use_case, called_at)` and `called_at`.

A Jev key gets the empty and whitespace rules only, no prefix rule: TypeSafe
documents no key prefix ([API reference](https://docs.typesafe.ai/api); its
Python SDK checks only that a key is non-empty with no whitespace, control
or non-ASCII characters; `jv_live_` keys belong to an unofficial reseller).
It is verified with `GET https://api.typesafe.ai/v1/models` and
`Authorization: Bearer <key>` (`engine.jev.upstream`), the SDK's
`models.list()`, which lists the account's models and spends no credits; a
decision call (`POST /v1/systemone`) would. A 401 or 403 is refused with
Jev's own reason (its FastAPI `detail`, a string or `detail.message`), as for
Anthropic.

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPOST localhost:8090/accounts -H 'content-type: application/json' -d '{"name":"jev-1","provider":"jev"}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPUT  localhost:8090/accounts/jev-1/credential -H 'content-type: application/json' -d '{"token":"<TypeSafe API key>"}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPOST localhost:8090/accounts/jev-1/verify
```

Re-adding a name answers 200 and leaves the row as it was; a `PUT .../credential`
without `ENGINE_SECRETS_KEY` answers 503. A `SmithSpec` names its `account`;
`DockerSandbox` refuses to start one whose account has no credential.

**The picker** (`AiAccounts.pick`, called by `startSmith`) chooses the account
for each run: among the accounts of the model's provider (`claude-*` bills to
`anthropic`, `gpt-*` to `openai`) that are `enabled` and have a credential, one
by the order below; among equals the one picked least recently (`last_used_at`,
never-picked first, then by name), so a provider's accounts rotate. A caller may pin one by setting the `account`
process variable. The account a run bills to, pinned or picked, is recorded in
its `billedAccount` variable (collect result, the usage meter and the Fable-limit
record read it there), never in `account`: that variable comes back to the
calling flow, and a later run there (a retry, the next round) is not pinned by it
and picks afresh (#178). A real Fable run whose pinned account is at its Fable
limit fails with "account <x> is pinned and at its Fable limit until <reset>". No usable account for the provider (an empty
table, all disabled, no credentials): a real run fails at "start smith" and
stays an incident until an account is usable and the job is retried; a dry run
starts anyway with no credential. The engine has no token of its own
(`engine.smith.claude-oauth-token` was removed 2026-09-24). 
The picker reads the plan meter (below). It skips an account while it is
rejected: its 5-hour status is `rejected` and `fiveHourReset` is still ahead,
or its 7-day status is `rejected` and `sevenDayReset` is still ahead, or a run's
result recorded a session or spend limit whose reset is still ahead. Once the
reset passes the account is picked again without anyone re-enabling it; an
account with `enabled = false` stays skipped whatever its resets say. Among
usable accounts, one whose 5-hour utilization is at or above 95%
(`FIVE_HOUR_SATURATED`, counted as in the 5-hour order below) goes after every
one below it, before the expiring rule applies; when all are, the order below
picks among them. Then those whose 7-day budget is expiring go first, most behind
first: expiring is needing at least an even week's pace to use up what is
left before the reset, `(1 - sevenDayUtilization) × 168h / hours to
sevenDayReset >= 1` (`EXPIRING_PACE`, `expiringPace`), so budget that would otherwise go unused
at the reset is spent before budget that would not. Then, for a model that is
not Fable (no model counts as not Fable), accounts whose Fable limit is reached
or whose Fable meter is out go ahead of the rest, so the others keep their Fable
budget for Fable runs; a Fable model never gets them at all. But for a model
that is not Fable, an expiring account that still has Fable headroom (limit not
reached, meter not out) is demoted after every other unsaturated account,
highest pace first among the demoted, so its 5-hour window stays free for the
Fable runs that can use up its expiring week; it is still picked when it is the
only usable one or the others are saturated, and a Fable model's order is
unaffected. The rest, and equal
ones, follow the 5-hour order: the lowest `fiveHourUtilization` (an
account with no reading, no utilization in it, or a reading whose 5-hour reset
has passed, counts as 0), then the least recently picked (never picked first),
then by name; an account with no reading, no 7-day utilization or reset, or a
7-day reset that has passed counts as not expiring. The 5-hour window gets no
expiry rule: its budget returns every 5 hours, and lowest-first keeps a run off
the 5-hour cap. A stale reading can only overstate what is left, so at worst an
account is favoured a little early; the gates above still apply to it. A real run whose pinned account is
rejected fails with "account <x> is pinned and rejected until <reset>". When
nothing is usable and at least one account was skipped as rejected, a real
run fails at "start smith" as an incident with `no account available until
<earliest reset>` (retry the job after it); this is checked before the Fable
gate, which still applies on top for `claude-fable-*` models.

**The summarize endpoint's account.** The summarize endpoint ("Summarize: a live run summarized") calls
Anthropic with the account the picker would choose next among the `anthropic`
accounts, found by `AiAccounts.peek`: the same order as `pick`, without
recording the use, so a summary never changes which account the next Smith gets.

**Which account each tier would get: `GET /tiers/accounts`** (#1260, `tier/TierAccounts.kt`).
One row per tier of the tier map, tier ascending, each `{tier, model, effort, account,
blockedUntil}`: `account` is what `AiAccounts.peek(providerOf(model), model)` names for that
tier's model at the time of the call, or `null` when none is usable; `blockedUntil` is then
`AiAccounts.blockedUntil(provider)` (for a `claude-fable-*` model the earlier of it and
`fableBlockedUntil()`), ISO-8601, and `null` whenever `account` is set. It is `peek`, never
`pick`: asking writes nothing, so it never moves an account's `last_used_at` or the rotation, and
it carries no credential or token. The desktop's Analytics > AI Accounts asks it when the view
opens and on its tile's Refresh ("Which account would be used?"); `read_tier_accounts` on the
diagnostics server answers the same rows.
