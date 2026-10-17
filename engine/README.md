# engine

A Kotlin / Spring Boot 4 service embedding the [Operaton](https://github.com/operaton/operaton)
process engine (the Apache-2.0 Camunda 7 fork), 2.1.x, on H2. In the
local stack the database is a file on the `engine-db` volume, so instances and
history survive `docker compose down`/`up` (`down -v` wipes it); run outside
Docker, it is in-memory and nothing survives a restart. On startup it logs `hello world`.

| Endpoint | Returns |
|---|---|
| `GET /echo?message=<text>` | `{"message": "<text>"}`; 400 if `message` is missing |
| `GET /health` | `{"status": "ok"}` |
| `PUT`/`DELETE /first-mate/push/device` | Registers or unregisters the phone's APNs device token (a `first-mate` token only); see "Push notifications" |
| `PUT`/`GET`/`DELETE /push/apns` | Sets (sealed), reads (`{set, setAt}` only) or clears the APNs credentials; see "Push notifications" |
| `GET` or `POST /camunda-test` | Starts one `hello` process instance: `{"processInstanceId": …, "ended": true}` |
| `POST /intake` | Starts one `intake` by its `ask submitted` message. Body: `{"ask", "dryRun"?, "fakeOutcome"?, "submittedBy"?, "authorize"?, "fromRun"?, "repo"?, "notifySession"?, "context"?}`; `context` is the filing session's summary of the conversation before the ask (not the user's words; no length limit, stored as bytes like `ask`; blank is none), filed after the quoted ask under "Conversation context" ("Intake"); `notifySession` is the submitting Claude Code session's name, told the filed ticket ("Telling the session that filed it"); `submittedBy` becomes the ticket's asker: the filed ticket's trailer says `by <login>` and its `engine.handled_issue` row and flow carry it as `askedBy` ("Intake", `by`); `repo` (`owner/name`) is the target repo the intake's ask work item and its ticket are filed in (400 if it is no target repo); required, except from a Smith sandbox, whose ask is filed in its run's own repo. There is no default repo. "Intake"; 202 with `{intakeId, repo, ticket, ticketUrl}`: `ticket` and `ticketUrl` are the ask work item's number and URL, known at once because `open the ask item` has no `asyncBefore` and runs inside the request; the refiner rewrites that item in place, so it is the filed ticket's number unless the refiner files in another repo ("Intake") |
| `GET /intake/{intakeId}` | `ended`, every step taken (every called process's included, each with `startedAt` and `endedAt`; an open step of the root flow while it runs, one that has started and does not wait on someone, also with `estimateMs`, `estimateBasis` and `estimateRuns`, "Stage estimates"), the variables, and its questions: `pendingQuestion` while it waits on one, `questions` for all of them; works for a chore's id too |
| `DELETE /intake/{id}` | Cancels a running root flow (intake, chore, bug, feature, proposal, or a bare `smith-run`; parked on an incident or not): stops its Smiths, deletes it, withdraws its pending questions, closes an open PR of its own unmerged, comments on the issue and closes it as not planned. Body: `{"reason"}`; the token's login acts, a `by` in the body is 400 (`by is not a field: the bearer token names the login`); 403 from a Smith sandbox or when that login is not trusted, 404 when not running, 400 for a called process or a blank reason. Returns the flow's state with `failedWrites`: each GitHub write that failed after the flow stopped. "Cancel and the sweep" |
| `POST /ticket/{owner}/{repo}/{n}/amend` | Adds the user's late information to a filed ticket whose flow runs, and loops the flow back to the work (`TicketAmender`; First mate's `amend_ticket`). Body: `{"text"}`; the token's login acts and must be trusted (403), a `by` in the body is 400 (`by is not a field: the bearer token names the login`). 403 from a Smith sandbox, by source address. Answers `{ok, repo, ticket, work, lost, failedWrites}`; its refusals (400, 404, 409) are in "Intake", "A follow-up to a ticket" |
| `POST /intake/{intakeId}/answer` | Answers the question the intake waits on. Body: `{"answer"}`; the token's login acts and must be trusted (403), a `by` in the body is 400 (`by is not a field: the bearer token names the login`), and the intake must be waiting (404). "Intake", the `ask` outcome |
| `GET /intake/notifications` | Every filed ticket still waiting to be pushed into the Claude Code session that filed its intake, oldest first, each with the `message` to relay. For the host-side watcher. "Telling the session that filed it" |
| `POST /intake/notifications/{id}/delivered` | The watcher's report for one. Body: `{"by", "result"}`. 404 unknown, 409 when already reported (a second watcher stops), 400 for a blank `by` |
| `GET /intake/tier` | The intake refiner's own tier, kept in the database: `{"tier", "model", "effort"}`, the model and effort from its row of the tier map ("Tier resolution"). "Intake" |
| `PUT /intake/tier` | Sets it. Body: `{"tier": n}`, a JSON integer 1-10; answers as `GET`. 400 for anything else (a string, a fraction, a tier outside 1-10, another key), with the stored tier unchanged. The next `refine` run takes it |
| `GET /accounts/consumption?window=1h\|4h\|1d\|1w\|30d` or `?since=<iso>[&until=<iso>]` | What the fleet consumed from the AI accounts in the window: `{"since", "until", "rows"}`, one row per ticket-round role, then per other Claude caller, then `total`, each `{"key", "caller", "calls", "costUsd", "durationMs", "inputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens", "unknown"}`, a figure null when unknown. 400 for neither, an unknown window or a bad instant. "Consumption ledger" |
| `GET /tiers` | The tier map, kept in the database: `[{"tier", "model", "effort"}]` for tiers 1-10 in order, the model as the full id a Smith runs with. "Tier resolution" |
| `PUT /tiers/{tier}` | Sets one tier's model and effort. Body: `{"model", "effort"}`, the model a short name (`haiku`, `sonnet`, `opus`, `fable`) or its full id; answers the row. 400 with the reason for a tier outside 1-10, an unknown model, an effort the model does not take (haiku takes `low` only; the others `low`, `medium`, `high`, `xhigh`, `max`), a missing or blank field, or another key, with the row unchanged. There is no `POST` or `DELETE`: always ten tiers. The next run that resolves the tier takes it |
| `GET /security/tier` | The tier every security review runs at: `{"tier", "model", "effort"}`. "Tier resolution" |
| `PUT /security/tier` | Sets it. Body: `{"tier": n}`, a JSON integer 1-10; answers as `GET`. 400 for anything else, with the stored tier unchanged. The next `security` run takes it |
| `GET /check/tier` | The tier every post-deployment check run (Smith mode `check`) runs at: `{"tier", "model", "effort"}`. `docs/smith-runs.md`, "The check mode" |
| `PUT /check/tier` | Sets it. Body: `{"tier": n}`, a JSON integer 1-10; answers as `GET`. 400 for anything else, with the stored tier unchanged. The next `check` run takes it |
| `GET /saved-chats` | The First mate chats saved for later from the desktop ("Saved First mate chats", below), newest `savedAt` first, without transcripts: each `id`, `title`, `startedAt`, `savedAt`, `repo` (null: All repos), `tier`, `claudeSessionId`, `savedFrom` (the saving desktop's host name), `turns` (user messages) and `messageCount` |
| `GET /saved-chats/{id}` | One saved chat: the list row plus `messages`, the strip's history in order (`{role, text, at, images?}`). 404 with `{"error"}` for an unknown id |
| `PUT /saved-chats/{id}` | Saves a chat under `id`, a UUID the desktop mints: inserts it (201) or updates the earlier save of the same conversation (200), stamping `savedAt` and deriving `turns` and `messageCount`; answers the list row. Body: `{title, startedAt, repo?, tier?, claudeSessionId?, savedFrom?, messages}`, `messages` 1 to 200 `{role: user\|assistant\|system, text, at, images?}`, `title` non-blank and at most 200 characters, `tier` an integer, `startedAt` an ISO instant or epoch milliseconds. 400 with `{"error"}` and nothing stored for an id that is not a UUID, another key, a wrong type, or a body over 1 MiB |
| `DELETE /saved-chats/{id}` | Deletes a saved chat: 204; 404 with `{"error"}` for an unknown id |
| `GET /questions` | Every question waiting on the user, across intakes, oldest first |
| `POST /chore` | Starts one `chore` on an issue by hand, the issue as it is now as the approved ticket. Body: `{"repo", "issue", "model"?, "effort"?}`; `repo` must be a target repo; 202 with `choreId`. The usual way in is a label: "Starting a flow from an issue" |
| `POST /scan` | Scans the target repos for approved issues now; returns what it started or refused |
| `GET /engine/update` | The Deploy button's status ("Deploying on Kubernetes"): `{mode, selfUpdate, running, image, latest, behind, updateAvailable, openSessions, error, checkedAt}`; kept 1 min, `?fresh=true` re-reads |
| `POST /engine/update` | The Deploy button's action: on Kubernetes what `POST /engine/restart` does (same `argoApp`, same refusals); 409 outside a cluster, whose host updates it |
| `POST /engine/restart` | On Kubernetes, restarts the engine's own Deployment so it pulls `:main` ("Images", "Deploying on Kubernetes"); 200 with `{namespace, deployment}` before the old pod stops. `?argoApp=<namespace>/<name>`: first waits up to 5 min for that Argo CD Application to sync (504, no restart, when it does not; 400 for a malformed name). 409 outside a cluster, 403 from a Smith sandbox |
| `POST /hooks/github` | GitHub's webhook deliveries ("When it scans"): checked against the webhook secret of the target repo the delivery names (`repository.full_name`); 401 without a valid `X-Hub-Signature-256`, or when it names no target repo with a secret; else 204 with an empty body; an allowlisted one wakes a scan, an `issues`, `issue_comment` or `pull_request` one is a `ticket` frame on the stream ("Events"; an open issue's carries its board row, read within 3 s), and a `push` one is a `push` frame and wakes no scan. The one path the Tailscale Funnel fronts |
| `GET /repos` | The target repos ("Its own tables", below): per row `fullName`, `addedAt`, `ciCheck` (null: no check), `ciPaths` (null or empty: every path), `hasToken` (the row holds its own GitHub token, "Each repo's token"), `tokenSetAt`, `securityReview` (false: its chores, bugs and features skip the security review, "Chore"), `scanIntervalMinutes` (how often it is scanned, "When it scans") and `scanCallsPerScan` with its parts `scanCalls` (`listing`, `authorized`, `deferred`, `checks`): the engine's estimate of the GitHub requests one scan of the repo makes, from its own state and without asking GitHub; `doctorBugs` and `engineDefects` ("Doctor"); `frictionBugs` ("Friction notes", "Two things"); `docsFirst` ("Docs path"); `childMode` (`serial`, `parallel` or `live`, "Split features"); `tokenPermission`, GitHub's collaborator permission of the repo token's account (`admin`, `maintain`, `write`, `triage`, `read`; null when the repo has no token or GitHub refuses or fails), and `callerPermission`, the same for the bearer token's login (null when GitHub refuses or fails). Both are asked with the repo's token and held in memory for the repo's scan interval, per repo and per repo and login, so a read costs no GitHub call per repo; a refusal never fails the read |
| `POST /repos` | Adds a target repo. Body: `{"repo": "owner/name", "ciCheck"?, "ciPaths"?, "securityReview"?, "scanIntervalMinutes"?}` (`securityReview` a boolean, default true; `false` from a Smith sandbox is 403, and 503 when Docker cannot say whether the caller is one, as for `PATCH`; `scanIntervalMinutes` 1 to 1440, default 60, and from a Smith sandbox 403 likewise; `doctorBugs`, `engineDefects` or `frictionBugs` is 400 naming the field: a repo is added with all three off); 201, or 200 when it is already there (its settings are then left as they were); 400 for a malformed name, a blank `ciCheck`, `ciPaths` that is not an array of non-blank prefixes without commas, or a `scanIntervalMinutes` out of range |
| `PUT /repos/{owner}/{name}/token` | Sets the repo's GitHub token ("Each repo's token"). Body: `{"token", "verify"?}`; tried against GitHub first unless `verify` is false (400 when it cannot read the repo or cannot push to it, 502 when GitHub is unreachable, nothing saved either way), then sealed on the row. 204; 404 for a repo that is not a target repo, 400 for a blank or spaced token, 503 without `ENGINE_SECRETS_KEY`. The token never comes back out: `GET /repos` says only `hasToken` and `tokenSetAt` |
| `DELETE /repos/{owner}/{name}/token` | Drops the repo's token: nothing runs on the repo until it is given another. 204; 404 for a repo that is not a target repo |
| `PUT /repos/{owner}/{name}/smith-token` | Sets the repo's Smith token, the `GH_TOKEN` of its Smith sandboxes instead of the repo token ("Each repo's token"): a fine-grained PAT with Contents and Pull requests read/write and no Issues permission. Body `{"token"}`; always tried against GitHub: 400 when it cannot read or push the repo (nothing about its issues is asked), 502 when GitHub is unreachable, nothing saved either way. 204; 404 for a repo that is not a target repo, 400 for a blank or spaced token, 503 without `ENGINE_SECRETS_KEY`. `GET /repos` says only `hasSmithToken` and `smithTokenSetAt`; a repo without one starts no Smith |
| `DELETE /repos/{owner}/{name}/smith-token` | Drops the repo's Smith token: no Smith runs on the repo until it is given another. 204; 404 for a repo that is not a target repo |
| `PUT /repos/{owner}/{name}/webhook-secret` | Sets the repo's webhook secret ("When it scans"): the value GitHub signs that repo's deliveries with, which goes in its webhook on GitHub too. Body: `{"secret"}`, at least 16 characters, no whitespace. Sealed on the row and never returned (`GET /repos` says `hasWebhookSecret` and `webhookSecretSetAt`). 204; 404 for a repo that is not a target repo; 400 for a malformed secret; 503 when `ENGINE_SECRETS_KEY` is not set; 403 from a Smith sandbox |
| `DELETE /repos/{owner}/{name}/webhook-secret` | Drops the repo's webhook secret: its deliveries are refused (401) and it runs on its scan interval alone. 204; 404 for a repo that is not a target repo; 403 from a Smith sandbox |
| `PATCH /repos/{owner}/{name}` | Edits the repo's engine check ("Chore", `wait for the engine check`), whether its flows run the security review ("Chore", `security reviews on?`) and its scan interval ("When it scans"). Body: a JSON object with only `ciCheck` (a string or null), `ciPaths` (an array of strings or null), `securityReview` (true or false, never null), `doctorBugs` and `engineDefects` (true or false, "Doctor": `engineDefects: true` while `doctorBugs` is or becomes false is 400, `doctorBugs: false` clears `engineDefects`, and `engineDefects: true` while another repo has it is 409 naming that repo; from a Smith sandbox 403, 503 when Docker cannot say), `frictionBugs` (true or false, never null, "Friction notes", "Two things": whether the engine files one bug per group of friction notes Jev marks as two things, in the note's repo; independent of the other two; from a Smith sandbox 403, 503 when Docker cannot say; the `changed` event names `frictionBugs`), `docsFirst` (true or false, never null, "Docs path": the repo's tickets default to the light docs path; from a Smith sandbox 403, 503 when Docker cannot say), `childMode` (`serial`, `parallel` or `live`, never null, "Split features": how a feature's split is cut and its children worked; anything else is 400 `childMode is serial, parallel or live`; from a Smith sandbox 403, 503 when Docker cannot say) and `scanIntervalMinutes` (a whole number of minutes from 1 to 1440, never null; anything else is 400 with a reason naming the range); a key present sets its column, null clears it, a key absent leaves it. A flow reads `securityReview` at its next push, so a change reaches a running flow at its next round. 200 with the updated row, 404 for a repo that is not a target repo, 400 for a malformed body (not an object, another key, a wrong type, a blank name or prefix), naming the field. A body with `securityReview` from a Smith sandbox (by source address, as for `POST /intake`) is 403: a Smith could otherwise switch off its own ticket's next security review; so is one with `scanIntervalMinutes`, which could spend the repo's token on scans every minute; 503 when Docker cannot say whether the caller is a Smith |
| `GET /settings/hitlp` | The HITLP server the engine talks to ("The HITLP requester", "The settings"; #2500): `{mcpUrl, requesterToken: {set, setAt}}`, never a secret; every value null or empty until set |
| `PUT /settings/hitlp` | Sets any of `mcpUrl` (an absolute http(s) URL with a host, trimmed, a trailing `/` dropped; null clears) and `requesterToken` (a non-blank string; null clears). 200 with the `GET` view; 400 for an empty body, another key or a wrong type (nothing changed); 403 from a Smith sandbox or without a bearer token that proves a login. A change publishes one `setting` frame named `engine.hitlp` with `{changed: [fields]}` as the value |
| `GET /settings/deployment-patterns` | Whether the deployment patterns are set ("Deployment patterns"; #100115): `{patterns: {set, setAt}, allow: {set, setAt}}`, never a value |
| `PUT /settings/deployment-patterns` | Body `{patterns, allow}`, each a string (one per line) or null; replaces both, an absent, null or blank one clearing it (`{}` clears both). Sealed under `ENGINE_SECRETS_KEY`. 200 with the `GET` view; 400 for another key, a wrong type or a pattern that is not a regex (naming its line number only); 403 from a Smith sandbox or without a proven login. A change publishes one `setting` frame named `engine.deployment-patterns` with `{changed: [fields]}` |
| `DELETE /settings/deployment-patterns` | Clears both; as `PUT {}` |
| `GET /repos/{owner}/{name}/refactor` | The repo's refactoring config ("Refactoring jobs"): `{repo, enabled, schedule, values, valuesDefault, maxTickets, maxLines, approval, jobId, updatedAt, updatedBy, tier}`; the defaults (off, weekly, the shipped values, 2 tickets of at most 300 lines, approval `human`, tier null) for a repo with no row. 404 for a repo that is not a target repo |
| `GET /repos/{owner}/{name}/mcp-servers` | The repo's First mate MCP servers ("First mate's MCP servers"), name order; a secret header or env value only as `{secret, set, since}`. 404 for a repo that is not a target repo |
| `PUT /repos/{owner}/{name}/mcp-servers/{server}` | Adds or replaces one: `{kind, audience, role?, url?, headers?, command?, args?, env?}`; 201 new, 200 replaced; 400 naming the field; 503 sealing a secret without `ENGINE_SECRETS_KEY`; 404 non-target repo; 403 from a Smith sandbox. `role: "ops"` (docs/feature.md, "The ops role") probes the server first and is 400 `ops server does not conform to ops/1: ...` unless it conforms; a `stdio` row with the role is 400. `GET .../{server}` reads one; `DELETE .../{server}` removes it (204, 404), 403 from a Smith sandbox |
| `POST /repos/{owner}/{name}/mcp-servers/{server}/test` | `{reached, tools, toolSpecs, error, contract?}`: MCP `initialize` and `tools/list` against an `http` or `sse` server; a `stdio` one answers that the desktop tests it; `contract` (`{version: "ops/1", conforms, missing}`) for a `role: ops` row. 403 from a Smith sandbox |
| `GET /repos/{owner}/{name}/mcp-servers/config` | `{"mcpServers": {...}}`, the repo's servers as `claude --mcp-config` takes them, secrets opened, for the desktop's First mate spawn. 403 from a Smith sandbox |
| `POST /repos/{owner}/{name}/deploys` | Tells the engine `sha` is now in production for the repo ("Deploys"): `{sha, source?}`, `sha` 7 to 40 hex digits (400 `sha is required: ...` otherwise, 400 for an unknown field), `source` who deployed. Writes an `engine.deploy_event` row, publishes a `deploy` frame (docs/events.md) and wakes the `DeployWaits` hooks; 201 with the row `{id, repo, sha, source, at}`; 404 for a repo that is not a target repo; 403 from a Smith sandbox |
| `GET /repos/{owner}/{name}/deploys?limit=` | The repo's last deploys, newest first (`limit` 1 to 500, default 20); 404 for a non-target repo; 403 from a Smith sandbox |
| `PUT /repos/{owner}/{name}/refactor` | Sets it. Body: a JSON object with any of `enabled` (true or false), `schedule` (whole seconds, at least 60), `values` (a non-blank text of at most 20000 characters, or null to return to the shipped `bots/refactor-values.md`), `maxTickets` (1 to 20), `maxLines` (10 to 5000), `approval` (`human` or `none`) and `tier` (1 to 10, the tier the refactor-scan ticket is filed and runs at; null files no scan); a key absent keeps the value; another key or a wrong value is 400 naming the field. Enabling creates the repo's one system job (`engine.job`, owner `system`, kind `refactor`, signal `every <schedule>`), disabling deletes it, a schedule change rewrites its signal. 200 with the config as it now is; 404 for a non-target repo; from a Smith sandbox 403 (by source address, as `PATCH`), 503 when Docker cannot say; the token's login is recorded as `updatedBy`; a `repo` frame `changed` with field `refactor` |
| `DELETE /repos/{owner}/{name}` | Removes a target repo. 204; 404 for a repo that is not one; 409 while any flow still runs on it (a process instance, suspended or not, whose `repo` variable names it, ignoring case), with `{"error", "flows": [{processKey, issue, instanceId}]}`, one per root flow, and nothing removed |
| `GET`/`POST /access`, `DELETE /access/{login}` | The engine-wide list of trusted logins. `GET` answers `{logins: [{login}], history}`; `POST` body `{"login"}`: 201 added, 200 already there, 400 for a bad login, any `flow` field or a `by` (the token's login is the actor); `DELETE` 204 (a `?by=` is 400), 404 when not on the list ("Starting a flow from an issue") |
| `POST /tokens` | Mints a bearer token ("Tokens", below) for a trusted login. Body `{"login", "name", "expiresInDays"?}`: 201 `{id, token, login, name, prefix, expiresAt}`, the one time `token` is shown; 400 naming a login not on the trusted list, a bad name or expiry; 401 without a bearer token once one exists (the first needs none: the bootstrap door); 403 from a Smith sandbox |
| `GET /tokens` | Every token row, newest first, without hashes: `id`, `login`, `name`, `prefix`, `createdAt`, `createdBy`, `expiresAt`, `lastUsedAt`, `revokedAt`. 401 without a bearer token once one exists; 403 from a Smith sandbox |
| `DELETE /tokens/{id}` | Revokes the token (sets `revoked_at`): 204; 200 when it already was; 404 `{"error"}` for an unknown id; 401 without a bearer token once one exists; 403 from a Smith sandbox |
| `GET /whoami` | `{login, tokenId, name}` for the bearer token the request carries; 401 `{"error": "no such token"}` without a valid one (a Smith's header is ignored, so a Smith gets 401 too) |
| `POST /jobs` | Registers a custom job ("Custom jobs and the SDK"). Body: `{"name", "kind", "repo", "config", "signal"}`; 201 with the row. 400 with the reason for an unknown kind, kind `refactor` (system-owned: `a refactor job is system-owned: enable it with PUT /repos/{owner}/{name}/refactor`, "Refactoring jobs"), a missing `repo` or one that is not a target repo (naming the target repos; there is no default), config keys the kind does not take, a bad signal (an `at` instant that is malformed or not in the future too), a bean or flow job's `jar` that is a path rather than a file name, a bean job's jar that cannot be loaded (missing, no `Frak-Plugin-Package`, a context that fails to refresh, no such bean, a bean that is not a `FrakJob`: "Custom jobs and the SDK"), or a flow job's jar whose diagrams break a rule, every problem listed one per line (`FlowRules`; "Flow jobs" below); 401 without a token, 403 from a Smith sandbox, 503 when Docker cannot say whether the caller is one |
| `GET /jobs?repo=<owner/name>` | Every job, oldest first, or one repo's: `{id, name, kind, repo, config, signal, createdBy, createdAt, nextRunAt, lastRunAt, lastInstance, owner}` (`owner` is `user` for a registered job, `system` for a repo's refactoring job) |
| `GET /jobs/{id}` | One job; 404 for an unknown id |
| `GET /jobs/{id}/runs?limit=` | The job's run history ("Refactoring jobs", "Run history"), newest first, `limit` 1 to 200 (default 20): `{jobId, repo, kind, instance, startedAt, endedAt, status, outcome, summary, scanIssue, scanEndedAt, scanOutcome, scanSummary, filed, skipped, foundNothing}`; `status` is `done`, `nothing` (filed nothing, `summary` says why) or `failed` (`outcome` says why), null while it runs; the scan fields are a refactor run's. 404 for an unknown id, 400 for a limit out of range |
| `DELETE /jobs/{id}` | Removes it; 204. 409 `job <id> is system-owned (refactor on <repo>): disable refactoring with PUT /repos/<repo>/refactor` for a system job (the diagnostics server's `delete_job` answers the same text; "Refactoring jobs"); 409 `job <id> is running in <instance>` while a run of it is active, and for a flow job 409 while any instance of its `job:<id>` deployment's definitions runs (the undeploy guard, "Flow jobs"); a flow job's delete undeploys its diagrams; the same 403s as `POST /jobs` |
| `POST /jobs/{id}/execute` | Starts one run now, whatever the signal: `{"instance"}`, the `job` process instance. 409 while a run of it is active; the same 403s as `POST /jobs` |
| `GET /repos/{owner}/{name}/changes?from=&to=` | What lies between two builds (#100091, the desktop's update preview modal): `{from, to, changes: [{sha, title, pr, prUrl, tickets: [{n, repo, url, title}]}]}`, the squash-merged PRs on `to`'s first-parent line since `from`, newest first, from GitHub's compare with the repo's token (up to 250 commits); each subject `Title (#ticket…) (#pr)` gives the PR and the tickets it closes, a commit with no `(#n)` is left out. Each ticket's `title` is the engine's work-item store's (the description's first line), null when the store has no item for it: no GraphQL, no issue read. 400 for a sha that is not 7 to 40 hex, 404 for a repo that is not a target repo, 502 when GitHub's compare fails |
| `GET /repos/{owner}/{name}/setup` | The repo's GitHub-side setup, read with the repo's token ("A new target repo's GitHub setup"): `hasToken` (false: the repo has no token, so nothing is readable), `readable`, `push` (the token's permission), `missingLabels`, `defaultBranch` (`name`, `exists`, `protected`, `requiresReviews`, `forbidsSquash`; null where GitHub will not say), `ciCheck` (the row's check: `name`, `ranOnDefaultBranch`, `commitsLooked`; null when the row names none) and `webhook` (`status` `present`, `missing` or `unknown`, and `hooks`: each hook's `events` and `active` only, never its payload URL, which can carry a secret). Makes no GitHub writes. 404 for a repo that is not a target repo, 502 when GitHub fails |
| `POST /repos/setup/check` | Asks for a check of the repos' GitHub-side setup and answers 202 at once, before any GitHub read; the result arrives as `setup` frames on the event stream ("A new target repo's GitHub setup", "Events"). Body `{"repo": "owner/name" \| "*", "force"?: true}` (`*`: every target repo); answers `{"queued": [...], "fresh": [...]}`: `queued` the repos a background check was started or was already running for, `fresh` those whose kept report was younger than 5 minutes and was re-published instead. 400 without `repo` (naming the target repos), 404 for a repo that is not a target repo, 403 from a Smith sandbox (503 when Docker cannot say) |
| `POST /repos/{owner}/{name}/setup/labels` | Creates the repo's missing kind labels and answers `{"created": [...]}`; a second call creates nothing. 404 for a repo that is not a target repo, 409 when the token cannot read it, 502 when GitHub fails |
| `GET /camunda/instances?issueUrl=<url>` | Every root process instance tied to a GitHub issue, newest first: the ones `handled_issue` started on it, the intake that filed it (its `issueUrl` variable) and any flow carrying it as `repo` + `issue`. Each: id, definition key and id, `startedAt`, `endedAt`, `state` (running, incident, ended) and the `step` it waits at. 400 for a URL that is not an issue's |
| `GET /camunda/instances/{id}` | The instance's call tree, root first: per process its definition id, the call activity it was called from, `completed` and `active` activity ids (a step waiting on its async job counts as active), `incidents` (activity, type, message, and `attempts`: a `failedJob` incident's job's every failed attempt from Operaton's historic job log, oldest first, each `{at, message, retriesLeft}`, the `message` being the last of them; `[]` for another type, "Incidents"), `timers` (the timer jobs it waits on: `activityId` and `dueAt`, such as `reusable-smith-run`'s `event-wait-reset`, "Incidents") and `variables` with the activity scope each was set in; values over 4000 characters are cut and flagged `truncated`. What the desktop app draws on the diagram. 404 for an unknown id |
| `POST /camunda/incidents/{id}/retry` | Retries an incident ("Incidents", below). No body needed; the token's login acts and must be trusted (403), a body or query `by` is 400. Answers `{incidentId, rootInstanceId, action, activityId}`, `action` being `job` or `smith`; comments it on the flow's issue. 404 for an incident that is not open, 400 for one that is not a failed job |
| `GET /camunda/definitions/{id}/xml` | The BPMN XML that process definition was deployed with (`application/xml`), so an instance draws on the version it ran. 404 for an unknown id |
| `GET /version` | `{"version", "commit"}`: the engine's build version, the git commit the image was built from (`engine.build.commit`, `ENGINE_BUILD_COMMIT`, set by the image, "Images"; null for a build given none) |
| `GET /issues` | The issues board: every open issue of every target repo with the engine's state joined on ("The issues board", below), `askedBy` (who asked for it; null when the engine has not seen it) included; `?repo=owner/name` keeps one target repo's, and reads only its listing from GitHub. `?state=closed&limit=n[&repo=owner/name]` is the done and cancelled work items instead, from the engine's store alone (no GitHub read): `{issues, failures}` (`failures` always empty), newest closed first, at most `n` (default 100, 1..500) across the target repos or the one named, each with `repo`, `number` (its ticket number), `title`, `url`, `labels`, `closedAt` (its last update), `stateReason` (`completed`, or `not_planned` for a cancelled one), `closedBy` (empty), `body` (the description after its title; `""` when empty) and `parent` (`{number, url}` of the split feature it was a part of, from the latest `feature` flow's `split` variable per issue in `engine.handled_issue`; null for any other). `&q=<text>` keeps those whose title or description holds the text, case-insensitive, same shape, same order and `limit`. A blank `q` is the plain listing. 400 for another `state` or a `repo` that is not a target repo, open or closed |
| `GET /needs` | Everything waiting on a person, oldest first ("What needs you", below): rows of `repo`, `number`, `title`, `kind` (`incident`, `insufficient-permissions`, `authorize`, `reauthorize`, `sighting`), `wanted` (one line), `since`, `actionId`, `question` and `for` (both null today; First mate's `read_needs` serves them too). A flow's question, acceptance or decision is no row: it is a task on the HITLP server ("The HITLP requester"), which the desktop and the phone read there directly (the engine is no surface, "No surface in the engine"). `?repo=owner/name` keeps only that target repo's rows (and lists only its issues); a row with no repo (an incident on a flow tied to no issue, the `sighting` row) is then left out; 400 for a `repo` that is not a target repo. `?for=<login>` keeps the rows whose `for` is that login (ignoring case) or null |
| `GET /doctor/bugs?since=<iso>` | The doctor bugs seen since `since` (default 7 days ago), newest first, each with its signatures and how many tickets and sightings it has ("Doctor", below); 400 for a `since` that does not parse |
| `PATCH /doctor/sightings/{id}` | Moves a sighting, its signature and the other sightings of that signature on the same bug: body `{"bug": "<owner/name>#<n>" \| null}`, to that issue's doctor bug (made when it has none) or off any bug; the unlump ("Doctor", "Jev, for a new signature"). 200 with the sighting as `GET /doctor/history` shows it. The token's login needs `bug` access on the repo of the bug it leaves and of the one it goes to (the stuck ticket's repo when neither); 403 without, and from a Smith sandbox (503 when Docker cannot say); 400 for a body with `by` or a `bug` that is not `owner/name#n`; 404 for no such sighting; 409 when it is already there. The login is the bearer token's; a body with `by` is 400 (`by is not a field: the bearer token names the login`) |
| `GET /events?repo=<owner/name\|*>` | The engine's event stream ("Events", below): a `text/event-stream` that never ends from the engine's side, `hello` first, then one frame per change (the twelve types of "Events": `intake`, `ticket` and `created` with a new issue's board row, `push`, `flow` with the issue's board row, `incident`, `needs`, `session`, `job`, `repo`, `setting`, `heartbeat`), `: ping` every 15 s while idle, no replay. `repo` is required: a target repo's full name (400 for one that is not, as `GET /needs`), or `*` for every repo; 400 without it, naming the target repos. 403 from a Smith sandbox or with an `Origin` header, 503 when Docker cannot say whether the caller is a Smith |
| `GET /ticket/{owner}/{repo}/{n}` | One ticket as GitHub has it: `repo`, `nodeId`, `number`, `title`, `body`, `bodyHTML` (GitHub's rendering), `state`, `url`, `labels`, `createdAt`, `closedAt`, `comments` (`id`, `url`, `author`, `createdAt`, `updatedAt`, `body`, `bodyHTML`) and `prs`, the PRs that close it (`number`, `title`, `url`, `state`, `isDraft`, `reviewDecision`, `mergeCommitSha`: the merge commit's sha once merged, the `headSha` of its main build, else null), and from the engine `askedBy`: who asked for it (`engine.handled_issue.asked_by`, "Intake", `by`), null when the engine has not seen the issue, and `points`: its story points (`engine.handled_issue.points`, "Intake", `points`), null when it has none. One GraphQL call (one more per 100 comments past the first). 404 with `{"error"}` for an issue GitHub does not have or a repo that is not a target repo, 502 when GitHub fails |
| `GET /ticket/{owner}/{repo}/{n}/comments` | Every comment on the issue, oldest first, as GitHub's REST API sends them (`GET /repos/{r}/issues/{n}/comments`, every page of 100), passed through unaltered: numeric `id`, `html_url`, `user.login`, `created_at`, `updated_at`, `body` and the rest. The desktop's answer flow reads these because the tickets server's `answers.question_fingerprint` digests the REST `id`, `updated_at` and `body`, which `GET /ticket`'s GraphQL comments do not carry. One REST call per 100 comments. 404 with `{"error"}` for an issue GitHub does not have or a repo that is not a target repo, 502 when GitHub fails |
| `GET /ticket/{owner}/{repo}/comments/{id}` | One issue comment by its REST id, as GitHub sends it (`GET /repos/{r}/issues/comments/{id}`): the answer flow's re-read just before it posts. One REST call. 404 with `{"error"}` for a comment GitHub does not have or a repo that is not a target repo, 502 when GitHub fails |
| `GET /ticket/{owner}/{repo}/{n}/pitch` | A proposal ticket's pitch, what the desktop's Pitch view shows, or a research ticket's latest answer page: `{kind, path, prUrl, content, round}`. A research ticket's answer comes first, from `engine.research_answer` with no GitHub call ("Research"): `kind` `html`, `path` null, `prUrl` empty, `round` the latest round, served after the ticket closes too; `round` is null for a proposal. For a proposal, It finds the ticket's latest `proposal` flow (`engine.handled_issue`) and reads its variables from history: the stored propose result (`proposal`), `branch` (default `smith/issue-<n>`) and `prUrl`. With a pitch, `kind` is `html` and `content` is the page as committed, read with the contents API (the repo's token, repo read) at the branch, or on the default branch once the branch is gone. With no such flow or no drafted result (the engine kept no record of the proposal), the one page under `docs/proposals/` the ticket's closing PR adds or changes (with several, the one whose `.md` it changes too; none when that does not decide), `kind` `html`, read from the PR's head branch while it is open and from the default branch once it is merged (`ProposalPitches`; one more GraphQL call, the default branch and the closing PRs with their files). A drafted pitch also carries `options` (`[{id, label}]`, the stored result's) and `awaitingChoice`, true while the ticket's proposal flow runs, waits on its question or its decision, and has options, and `awaitingDecision`, true when that wait is the decision ("Picking an option" under "Proposal"); both are empty/false otherwise. With a record but no pitch (a draft from before pitches), `kind` is `markdown` and `content` is the `.md` read the same way and rendered by GitHub's `POST /markdown` (gfm, in the repo's context). Two or three GitHub calls, so not for polling. 404 with `{"error"}` for a repo that is not a target repo, a ticket that is not a proposal, one whose propose Smith drafted nothing or that has no PR, or a file on neither ref; 502 when GitHub fails |
| `GET /proposals` | The proposal docs on each target repo's default branch, what the desktop's Proposals branch lists (desktop #660): `{docs, errors}`, `docs` one per `docs/proposals/*.md` with `repo`, `slug`, `path`, `title` (its first `# ` line), `status` (its `Status:`, one of `proposed`, `accepted`, `shelved`, `rejected`, `built`, else null), `decisionLog` (the `Decision log:` items in order), `ticket` (its `Ticket: #n`), `buildTicket` (the last `build filed as #n` in the log, which `task-log-decision` writes, else a `Builds: #n` line) and `pitch` (the sibling `<slug>.html`, if there is one). Only the doc's head, above its first `## ` heading, is read, so a template in its body is not taken for its own. `?repo=owner/name` reads that target repo only; without it every target repo, and one whose read fails is an `errors` entry (`{repo, error}`) rather than a failure. One GraphQL call per repo lists the directory with each blob's oid (`HEAD:docs/proposals`); a doc is parsed once per oid and kept in memory (`ProposalDocs`), so a refresh with no doc changed costs that one call, and one more (every changed blob, aliased in one query) when some did. 404 with `{"error"}` for a `repo` that is not a target repo, 502 when GitHub fails for it |
| `POST /ticket/{owner}/{repo}/{n}/predict-review` | The review prediction the desktop's review tile shows (#1746): Jev asked whether the ticket's live review run will end `approved`, with the run's session digest as the state; `{runId, callId, verdict, confidence, value, lines, fresh}`, `value` in -100..100. An unchanged transcript answers the kept prediction with no Jev call. 409 with no live review run, 404 for a repo that is not a target repo, 403 to a Smith, 503 with no usable Jev account, 502 when Jev fails. "Predict a review: Jev on the live review run"; the series of calls: `read_review_predictions` ("The diagnostics server") |
| `GET /proposals/{owner}/{repo}/{slug}/page` | One proposal doc's page from the default branch, in `GET /ticket/.../pitch`'s shape with an empty `prUrl`: `docs/proposals/<slug>.html` as `kind` `html`, else `<slug>.md` rendered by GitHub's `POST /markdown` as `kind` `markdown`. What the desktop's proposal view shows for a doc with no ticket to read the pitch through. One to three GitHub calls, so not for polling. 404 with `{"error"}` for a repo that is not a target repo, a slug that is not a plain file name, or no such doc; 502 when GitHub fails |
| `GET /repos/{owner}/{repo}/tree` | A target repo's whole tree at a ref, what the desktop's Repos branch browses (#674): `{commit, truncated, entries}`, `commit` the SHA the ref resolved to, `truncated` GitHub's flag (its recursive read stops at 100,000 entries or 7 MB) and each entry `{path, type, sha}` as GitHub's recursive tree gives it (`type` `blob`, `tree`, or `commit` for a submodule). `?ref=<branch, tag or SHA>` reads that ref; without it the default branch. The ref is resolved with one REST call (`GET /repos/{r}/commits?sha=<ref>&per_page=1`), skipped when it is a commit SHA the engine already holds a tree for; the tree is one `GET /repos/{r}/git/trees/{commit}?recursive=1`, kept in memory by `repo@commit` (the last 32, `RepoFiles`), so a second call with the head unchanged costs the resolve and no tree read. The engine keeps no repo's head SHA anywhere else, so there is none to reuse. Nothing polls it. 404 with `{"error"}` for a repo that is not a target repo or a ref GitHub cannot resolve; 502 when GitHub fails. To a Smith sandbox (by source address, as for the summarize endpoint) whose ticket is on another repo, 403 with `{"error"}`: the engine's token reaches every target repo; 503 when Docker cannot say whether the caller is a Smith |
| `GET /repos/{owner}/{repo}/file?path=<p>` | One file at a ref, its bytes whole: `Content-Type` from its extension (`.md` `text/markdown`; `application/octet-stream` when the extension says nothing), its blob SHA as `X-Blob-Sha`, sent with `Content-Security-Policy: sandbox` and `nosniff` so a repo's `.html` is never a page of the engine's origin. `?ref=` as for the tree (the desktop passes the tree's `commit`, so a file matches the tree it was picked from). The blob SHA comes from the commit's tree (read, or kept, as above); the bytes are one contents read (`GET /repos/{r}/contents/{path}?ref=<commit>`, GitHub's raw media type), kept in memory by `repo@blob` (64 MiB in all, a file over 8 MiB not kept), so a path whose blob is unchanged, at this commit or a later one, costs no contents read. In a truncated tree that does not list the path, the contents API is asked directly and the bytes kept by their git blob SHA. 404 with `{"error"}` for a repo that is not a target repo, a ref GitHub cannot resolve, or a path that is no file there (missing, a directory, or not a plain relative path); 502 when GitHub fails; 403 to a Smith sandbox on another repo and 503 when the caller cannot be told from a Smith, as for the tree |
| `GET /repos/{owner}/{repo}/git/info/refs?service=git-upload-pack` | The ref advertisement of git's smart-HTTP read protocol, forwarded to `https://github.com/{owner}/{repo}.git/info/refs?service=git-upload-pack` with `Authorization: Basic x-access-token:<the repo's token>` ("Each repo's token"), so `git clone $ENGINE_URL/repos/<o>/<n>/git` (and every `git fetch` of that clone) works from a machine with no GitHub credential: what First mate's fresh clones use. A passthrough (`board/RepoGitController.kt`), not a git implementation: the engine keeps no clone, `Content-Type`, `Content-Encoding`, `Git-Protocol` and `Accept` go through as the client sent them, GitHub's status, `Content-Type` and `Content-Encoding` come back as it sent them, and the body is streamed, never buffered. The upstream is `engine.github.git-url` (default `https://github.com`; tests point it at a stub). `service=git-receive-pack`, or any other service, is 403 with `{"error"}` and GitHub is never asked: through the engine a repo is read-only. 404 with `{"error"}` for a repo that is not a target repo or has no token; 502 with `{"error"}` when GitHub cannot be reached or answers 5xx (its 401/403/404 goes through as it is, so git shows GitHub's message); 403 to a Smith sandbox on another repo and 503 when the caller cannot be told from a Smith, as for the tree |
| `POST /repos/{owner}/{repo}/git/git-upload-pack` | The upload-pack exchange a clone or fetch sends after the advertisement, forwarded to `https://github.com/{owner}/{repo}.git/git-upload-pack` the same way: the request body (the client's wants and haves, `Content-Type: application/x-git-upload-pack-request`, gzip when the client chose it) streamed up as it arrives, the pack streamed down as GitHub sends it, with a flush per chunk. `POST .../git/git-receive-pack` (a push) is 403 with `{"error"}` and GitHub is never asked. Errors as for `info/refs` |
| `GET /ticket/{owner}/{repo}/{n}/timeline` | One ticket's timeline from history, in the contract the desktop Timeline widget reads: `ticket`, `steps` (one per Smith run), `waits` (the gaps between them, by cause) and `totals` ("Ticket timeline", below). Reads the same roots as `/camunda/instances`; no GitHub call. 404 with `{"error"}` when no process instance is tied to the issue |
| `GET /jev/calls` | The `engine.jev_call` rows newest first, paged (`limit` 1..500, default 50; `offset`): `{total, limit, offset, calls}`, each call `id`, `called_at`, `use_case`, `process_instance`, `repo`, `issue`, `model`, `latency_ms`, `threshold`, `action`, `outcome`, `no_answer`, and the `pick` and `confidence` read from the response (refine-pick's fields joined, `feature · confirm · tier 6`, at the lowest confidence); never the request, the response or the account. Filters: `use_case`, `repo`, `issue`, `process_instance` (the flow that made the call), `action` (a prefix), `outcome` (`set`, `unset`, or a prefix), `since` (an ISO instant or date, inclusive), `until` (the same, exclusive; with `since`, one run's window). 400 for a bad `limit`, `offset`, `since` or `until`. "Jev decisions" |
| `GET /jev/calls/{id}` | One call in full: the row's fields plus `outcome_at`, the `request` as sent with its `state` and `questions`, the `response` as received (JSON, or the text when it is not) or the `error`, and `picks`, per question the pick, confidence and probabilities. 404 with `{"error"}` for an unknown id |
| `GET /jev/summary?since=<instant or date>` | Per use case over the window (default the last 7 days; the nine the engine asks always there, `review-prediction` among them): `calls`, `noAnswer`, `medianLatencyMs`, `picks` (per question, option to count), `confidence` (ten 0.1 buckets) with the `floor` in force, `actions` (variable parts dropped), `withOutcome`, `judged`, `agreed`, `agreement` (null until something is judged), `runsSaved` (ci-triage and incident-triage only) and `perDay` (UTC); plus `totals` over every use case. "Jev decisions" |
| `GET /friction/notes?since&repo&kind&source&limit` | The friction notes (`engine.friction_note`, "note_friction") newest first, each `{id, repo, issue, run_id, process_instance_id, noted_at, source, kind, summary, evidence, severity, login, signature, group_id, grouped_by, jev_call_id, grouped_login, jev_confidence, two_things, two_things_call_id}` (the last eight from "Friction notes": the exact key, the group, how it joined, the `friction-group` call, the login that pulled it out, the call's `group` confidence, and Jev's two-things mark with its call, null while ungrouped, without one, or unmarked); every parameter optional (`since` an ISO instant or date), `limit` 100 by default and capped at 500; 400 for a `kind` or `source` that is none of the store's. "Friction notes" |
| `GET /friction/groups?since&repo&kind&source&state&limit` | The friction groups with at least one member passing the same filters, newest passing member first, each `{id, title, count (members passing), total, newest, oldest, kind (the majority; "mixed" on a tie), repos, severity (the highest), bug ({repo, issue, state}: the group's two-things bug, null without one), fix ({repo, issue, state, recurredAt}: the ticket that fixes it; {intake, repo, issue: null, state: "fix-pending"} while only an intake is linked; null without either), state (`open`, `fix-pending`, `fix-filed`, `fixed`, `recurred`), members}`, the members as `/friction/notes` rows; `state` keeps groups in that state (`unsolved`: all but `fixed`), applied before `limit`; `limit` counts groups, 100 by default, capped at 500; the same 400s, and 400 for an unknown `state`. "Friction notes", "Fixed by" |
| `POST /friction/fixes/sync` | One pass of the friction fix sync now (#1207): answers `{linked, changed, recurred, skipped}`; 409 while a pass runs; 403/503 for a Smith sandbox as the corrections. "Friction notes", "Fixed by" |
| `POST /friction/groups/{id}/fix-intake`, `POST /friction/notes/{id}/fix-intake` | A candidate fix by intake id (#1337): `{"intake": "<id>"}` links the group (or the note's group) to the intake `submit_ask` / `POST /intake` answered: `fix-pending` until it files its ticket, then `fix-filed` on that ticket. 400 for another key, a blank `intake`, or a body with `by`; 404 for no such group, note or intake; 409 for an ungrouped note, a group already fixed by a ticket, or an intake that ended without one; 403/503 for a Smith sandbox (a Smith calls `link_friction_fix_intake`). Answers the group as a `/friction/groups` row. "Friction notes", "Fixed by" |
| `PATCH /friction/groups/{id}` | Fixed by (#1200): `{"fix": {"repo": "owner/name", "issue": <n>}}` links the group to the ticket that fixes it (`fix-filed` until the sync reads the ticket); `{"fix": null}` clears it, and the sync does not link that ticket again. 400 for another key, a bad repo or issue, or a body with `by`; 404 for no such group; 403/503 for a Smith sandbox as the other corrections. Answers the group as a `/friction/groups` row. "Friction notes", "Fixed by" |
| `PATCH /friction/notes/{id}` | Two corrections, told apart by the key present. Pull out: `{"group": null}` makes a new group holding that note alone, titled by its summary line, `grouped_by` `user`, `grouped_login` the bearer token's login; the note's key goes with it unless another member of the old group shares it; a `jev` note's call gets its outcome; 409 for a note not grouped yet or alone in its group. Not two things: `{"twoThings": null}` clears Jev's two-things mark, files and closes nothing, and writes `two things: cleared by @x` on the mark's call; 409 for a note not marked. Any login, the bearer token's; 400 for a body with `by` (`by is not a field: the bearer token names the login`), a non-null `group` or `twoThings`, a body with both keys or neither (naming the two), or another key; 404 for no such note; 403 to a Smith sandbox by its source address, 503 when the sandbox provider cannot be asked. Answers the note as a `/friction/notes` row. "Friction notes" |
| `POST /friction/groups/{id}/merge` | Merge: `{"into": <id>}` moves every note and signature of group `id` into `into`, which keeps its title, and deletes `id`; the founder's call and the receiving group's `jev` calls get their outcomes. 404 for either id missing, 409 for a merge into itself, the same login (a body with `by` is 400) and sandbox rules. Answers the receiving group as a `/friction/groups` row. "Friction notes" |
| `GET /friction/summary?since&repo` | Over the window (default the last 7 days, at most 366; an earlier `since` is a 400) to now: `{from, to, total, per_day: [{day, count}] (every UTC day, zeros included), by_kind, by_source, by_severity (plus "unset"), by_repo ("none" for First mate notes with no repo), groups (the window's distinct groups), ungrouped (its notes the grouper has not placed yet)}`. The totals count raw notes. "Friction notes" |

From a Claude session in this repo: `submit_ask` on the engine's `frakworks`
MCP server posts an ask (a refiner's question is decided on the HITLP server), and
`/test-intake` (`.claude/skills/`) posts a fixed, harmless one that should
take the `file` path. All of them write to real GitHub issues. The `/intake`
skill, which did the same with `curl` and its own token, was removed on
2026-10-10.

Processes under `modules/app/src/main/resources/bpmn/` are auto-deployed at startup.
`hello.bpmn` is start → `delegate 1` → end; the delegate (`HelloDelegate`)
logs `hello from delegate`. The Camunda Modeler diagrams in `bpmn/` are
deployed too (the folder is a resource directory), so a save in the Modeler is
what the next build runs.

No endpoint is authenticated.

## The engine docs

The engine's reference is split by topic under [`docs/`](docs/README.md). Each topic
below keeps its old heading here, so a reference such as `engine/README.md, "Smith runs"`
still finds its place: follow the link. A quoted subsection name (say "Doctor" or
"Each repo's token") is listed under the topic that holds it.

## Engine modules

Moved to [`docs/engine-modules.md`](docs/engine-modules.md).

## Smith runs

Moved to [`docs/smith-runs.md`](docs/smith-runs.md). Sections: "The ride-out proxy", "Hosting models", "The retro", "Each repo's token", "The token's scopes", "GitHub calls and the rate limit", "The frakworks server", "The frakworks server in a Smith", "Engine-served MCP servers", "The judge server", "SSH verdicts", "Rubric consolidation", "The HITLP requester", "The public URL", "The diagnostics server".

A work run never pushes: its commits leave the sandbox as `commits.bundle` and `open/update pr` pushes them after the run. Work in progress goes out mid-run through the frakworks server's `push_chunk` tool (#100043; `docs/smith-runs.md`, "The frakworks server in a Smith"): a base64 git bundle of `<START>..HEAD`, pushed by the engine to `smith/issue-<n>` fast-forward only, which publishes the `push` event a live parent's `await_children` wakes on and triggers nothing else; the check, the security review, the PR and the review run once, on the done push (`docs/chore.md`, "open/update pr"). `bots/smith.md` §6 says when a Smith pushes a chunk.

### Deployment patterns

Every Smith push, a `push_chunk` or the done push of `commits.bundle`, goes through the engine's one git push (`CliGitPush.push`), and that is where deployment values are refused (#100115): before pushing, the lines the bundle's commits add since what the branch already has (the default branch on a first push) are checked against the deployment patterns, one extended regex per line (blank lines and `#` comments skipped), an added line equal to one of the ALLOW lines passing. A hit refuses the push as a rejected result naming file, line and commit only, never the pattern or the line; nothing is pushed. The patterns are set by `PUT /settings/deployment-patterns` and stored sealed in `engine.deployment_patterns` (V102) under `ENGINE_SECRETS_KEY`, as tokens are: never in a property, a config file, the image, a log or a Smith's sandbox (no env key carries them; `run-smith.sh` does not check). Unset, nothing is checked. Interactive pushes are checked by the same rules on the user's machine: `scripts/install-hooks.sh` and `scripts/check-deployment-values.sh` (`scripts/README.md`).

A frakworks Smith runs the engine test class(es) it changes in its sandbox before each round, `cd engine && gradle :engine-<module>:test --tests <Class> --no-daemon --offline`, from the dependency cache and Gradle config baked into the Smith image (`smith/gradle.properties`); never the full build, which the engine check runs on every push (`docs/smith-runs.md`; `bots/smith.md`, "Engine work"). Gradle runs one invocation at a time, in the foreground: the sandbox's Gradle config fits exactly one JVM, so two at once, one in the background (`nohup`, `&`, `run_in_background`, a Monitor) or a loop of runs is OOM-killed, and a verify run killed so (exit 137) reports `unverified`, not a verdict (`smith/run-smith.sh`, `BUILD_TEXT` and the verify prompt).

`run-smith` hands `claude` its prompt on stdin from a file under the run's work directory (`prompt.md`, mode 0600), never as a command-line argument, so a brief of any size runs: Linux caps a single argument at 128 KiB (`MAX_ARG_STRLEN`), above which `execve` failed with exit 126 (#100102).

## Custom jobs and the SDK

Moved to [`docs/custom-jobs-and-the-sdk.md`](docs/custom-jobs-and-the-sdk.md). Sections: "The job registrar".

## Refactoring jobs

Moved to [`docs/refactoring-jobs.md`](docs/refactoring-jobs.md).

## Intake

Moved to [`docs/intake.md`](docs/intake.md). Sections: "Telling the session that filed it", "Tier resolution", "Structured output", "Smith results".

A refine result is `file`, `insufficient-permissions`, `ask` or `withdrawn`: the last when the user's answer withdraws the ask (drop it, or another ticket already covers it), and the engine then closes the ask item as not planned, quoting the reason, and files and starts nothing.

An intake opens no GitHub issue: its ask is an engine work item of kind `ask` from the first step, whose ticket number carries the intake's comments, its refiner's questions and its close, and which `File ticket` refines in place into the ticket. An untrusted submitter's intake waits until a trusted login starts that item (`POST /work-items/{id}/start`, or First mate's `approve`); a trusted submitter's starts at once.

## Chore

Moved to [`docs/chore.md`](docs/chore.md). Sections: "Bringing the PR up to date", "Why the engine check runs before the reviews", "Hitlp changes wait for the user", "Docs path".

## Flaky tests

The engine's Gradle build retries a failed test (`org.gradle.test-retry`, applied to every module in `build.gradle.kts`'s
shared `Test` block): `maxRetries = 2`, `maxFailures = 10` (a task with more failures is broken, not flaky, and is not
retried), `failOnPassedAfterRetry = false`. A test that fails every attempt still fails the build with its
`Class > test() FAILED` line. One that passes on a retry passes the build and prints, at the end of its task, one line:

```
FLAKY-TEST: <fully.qualified.Class> > <method>() passed on retry
```

The line never ends in `FAILED`, so `CiLog.failures` and the workflow's retry ignore it.

**Tickets come from `main`'s build only.** A pull request's check cannot tell a flake the branch introduced from one
already on `main`, so it files nothing. On each merge, `.github/workflows/images.yml` builds the engine image (its
`build` job, matrix entry `frakworks-engine`, whose `gradle build` prints the same lines). `MainBuildFlakes` reads every
completed `images` run on the default branch of the repo flagged `engineDefects` (codefrak-oss/frakworks) once ever: it
runs in the engine-check pass (`CiCheckWaits`: on each repo's scan interval, at startup, on `POST /scan` and on a
webhook wake), and keeps the last run it read per repo in `engine.flaky_build_watermark` (V101), so a second pass or a
restart reads nothing twice. A run still going stops the pass there until it completes (its log is not final); a
failed or cancelled run files nothing; a run whose log cannot be read is read again on the next pass. For a successful
run it reads the engine job's log (`CiLog.flaky`) and `FlakyTests` files each such test as a `bug` ticket in that repo
titled `Flaky test: <Class>.<method>`, naming `main`'s build (the run and its URL), the merge commit and the job, and
starts its bug flow on its own at tier 5 (`AuthorizedStart`, label event 0, `authorized` set as the visible record),
authorized by the engine's flaky-test filer: no person starts it, and it never waits in What needs you. The title is
the key: a later sighting while that ticket is open only comments `Seen again: ...` on it (no new ticket, no start);
once it is closed, a new ticket is filed and started. A failure to read, to file or to start is logged and changes
nothing else; a ticket whose start failed stays filed, startable by a trusted login. Accepted caveat: the shared Gradle
build cache runs a module's tests on `main` only when its classes changed, so a flake in an untouched module stays
unseen until that module changes.

**A flaky-test ticket is fixed optimistically** (#100074). Proving a flake in the 2-CPU, 3 GiB sandbox takes hours, and
being wrong is cheap: `main`'s build sees it again and files a new ticket. So:

- **No reproduce run.** Every start `FlakyTests` makes carries the flow variable `flakyTest` (`Vars.FLAKY_TEST`, passed
  through `AuthorizedStart`; nothing else sets it). In `bpmn/bug.bpmn` the gateway `flaky test?` after the start sends
  such a flow straight to `call-do-work`; the brief's `reproduction` stays null.
- **One work run, the class run once.** The brief's `flakyTest` is true, and `smith/run-smith.sh` gives the work run a
  flaky-test text in place of the reproduction's: read the test and the code it touches, fix what looks wrong (shared
  state, ordering, timing, a duplicate side effect), run the changed class once with
  `cd engine && gradle :engine-<module>:test --tests <Class> --no-daemon --offline`, never in a loop or more than once a
  round, no regression test for the flake, and a summary line starting `Suspected cause:`. The ticket's body names the
  same command, its module read from the build log's `> Task :<module>:test` line. The PR gets the engine check and
  both reviews as any bug's; the review is told there is no reproduction to check against.
- **No verify run.** After the merge, `task-merge-pr`'s `flaky test, no verify` flow skips `call-verify`, and the
  `Done.` comment (`CloseTicketDelegate`) says `Optimistic flaky-test fix, not verified. Suspected cause: <the work
  summary's line>` (`not named` when it has none) in place of the verify text.
- **A repeat names the earlier fix.** A sighting after the ticket closed files a new ticket, as above, whose body
  carries after the Goal's first paragraph `Fixed before in #<n> (<cause>), seen again on <yyyy-mm-dd>.`: the newest
  ticket with that title (`Tickets.newestTitled`), the cause read back from its `Done.` comment (`unknown cause` when it
  has none), and the sighting's UTC date.

Escalation (reproducing a flake under the build runners' conditions) is not built: it will be only if an optimistic fix
comes back twice.

**On a pull request, the reviewer judges.** When the engine check passes on the `engineDefects` repo, `readCiCheck`
keeps its `FLAKY-TEST` lines as `<Class> > <method>()` in the flow variable `ciFlaky` (removed by every push, cleared by
a failed check), and the correctness review's brief carries them as `ci` with the check's result. The review prompt
makes one a finding only if the test is in code the branch changed, or its flakiness is plausibly caused by the branch;
otherwise the reviewer says nothing and `main`'s build catches it. That judgement is the only PR-side check. The work
Smith's brief does not carry them: it is not told it got lucky. A work Smith whose changed test class fails and then
passes in the sandbox judges that itself, and fixes it when its own work caused it (`bots/smith.md` §6).

## Post-deploy check

Moved to [`docs/post-deploy-check.md`](docs/post-deploy-check.md): what a chore, bug or feature does after its merge when its PR has a `POST-DEPLOYMENT-CHECK` section.

## Bug

Moved to [`docs/bug.md`](docs/bug.md).

## Feature

Moved to [`docs/feature.md`](docs/feature.md). Sections: "The design deck" (a confirm design's slide deck, `GET /ticket/{owner}/{repo}/{n}/design-deck`), "Split features", "Private documents", "First mate's MCP servers", "Answer attachments", "The brief: what a Smith is told", "Lineage", "What each mode writes".

A live parent (`childMode` `live`) waits on its children with `await_children`, a parent-only tool on the `frakworks` MCP server: it blocks on the event bus until one of its split's children has a significant change (a run ending with its outcome, a push, a question or incident, a merge or close; run starts and steps inside a run do not wake it), waits 30 s more to gather what follows, and answers every change since the cursor, bookkeeping included, or `{changed: false}` at the cap (`PUT /settings/parent-await-cap`, default 900 s). It is answered as an SSE stream with a progress notification every 30 s, so `claude -p` keeps the call open; `read_transcript` takes a cursor for the parent too. Each answer carries the run's `deadline` and `remainingSeconds`. One parent session spans the whole split: when a close readies a draft it files the next children itself with `file_children` (the flow's own filing, `ChildFiling`; the next `await_children` watches them too) and ends `done` once every draft is filed and every child closed, with a turn budget of its own (`engine.smith.parent-turns`, 1000; `docs/smith-runs.md`, "Turn budget"); `ended` keeps the fallback. A child's question under the running parent goes to the parent, not the user: `await_children` reports it as a `question` change with its text, and the parent answers it with `answer_child({child, answer})` (the child's next brief carries the answer by `#<parent>`) or hands it to the user with `answer_child({child, escalate: true, note?})`, which files exactly the HITLP request and `/needs` row the child would have had; a parent session that ends with questions waiting hands them all to the user (`docs/feature.md`, "Children ask the parent"). The parent keeps notes for a later session with `note_for_next_parent`, stored at once in `engine.parent_note`; a parent run's brief carries every earlier note of its split (`parentNotes`) and its `deadline`. Details: `docs/feature.md`, "Split features", "Live parent", "file_children", "await_children", "Children ask the parent", "Notes across waves".

## Proposal

Moved to [`docs/proposal.md`](docs/proposal.md).

## Research

Moved to [`docs/research.md`](docs/research.md).

## Process migration

Moved to [`docs/process-migration.md`](docs/process-migration.md).

## Incidents

Moved to [`docs/incidents.md`](docs/incidents.md). Sections: "Fix and Propose a fix", "Doctor".

## Friction notes

Moved to [`docs/friction-notes.md`](docs/friction-notes.md).

## Saved First mate chats

Moved to [`docs/saved-first-mate-chats.md`](docs/saved-first-mate-chats.md).

## Ticket timeline

Moved to [`docs/ticket-timeline.md`](docs/ticket-timeline.md).

## Summarize: a live run summarized

Moved to [`docs/summarize-a-live-run-summarized.md`](docs/summarize-a-live-run-summarized.md).

## Predict a review: Jev on the live review run

Moved to [`docs/predict-a-review-jev-on-the-live-review-run.md`](docs/predict-a-review-jev-on-the-live-review-run.md).

## Diagram rules

Moved to [`docs/diagram-rules.md`](docs/diagram-rules.md).

## Its own tables

Moved to [`docs/its-own-tables.md`](docs/its-own-tables.md). Sections: "A new target repo's GitHub setup".

## The issues board

Moved to [`docs/the-issues-board.md`](docs/the-issues-board.md). Sections: "Builds in progress", "CI runs of a repo", "What needs you", "Database pool".

## Replicas and the lease

Moved to [`docs/replicas-and-the-lease.md`](docs/replicas-and-the-lease.md).

## Events

Moved to [`docs/events.md`](docs/events.md).

## Deploys

The engine's one answer to "is commit X in production for repo R?", and one way to be told of a deploy (#100008, part A of
the design of #100007; no flow uses either yet). `DeployedChecks.isDeployed(repo, sha)` (`deploy/DeployedChecks.kt`,
plugins) answers `deployed` or `not_yet`: for the engine's own repo (`engine.build.repo`, the Dockerfile's `GIT_REPO` as
`ENGINE_BUILD_REPO` beside `engine.build.commit`) from its build commit, equal or an ancestor by GitHub's compare
(`github/GitHubCompare.kt`, `sha...commit` `ahead` or `identical`); for any other repo through its MCP server with role
`ops`, which conforms to the `ops/1` contract (docs/feature.md, "The ops role"); without one it throws `NoOpsServer`
naming what the repo needs. `POST /repos/{owner}/{name}/deploys {sha, source?}` (`deploy/DeployController.kt`, api)
records a row of `engine.deploy_event` (V96; `repo`, `sha`, `source`, `at`), publishes the `deploy` frame and calls every
`DeployWaits` bean (`deploy/DeployEvents.kt`: an interface the flow part registers the real one for; none here, so a
deploy wakes nothing yet); `GET .../deploys?limit=` lists the last rows. Both are 403 from a Smith sandbox, as every
`/repos/**` write: a Smith may not mark its own merge deployed. At `ApplicationReadyEvent` the engine posts a
deploy event for its own repo with its build commit (`deploy/EngineDeployAtStart.kt`, source `engine start`), skipped when
the repo already has a row for that commit, so a restart of the same image adds nothing; nothing is posted when the build
repo or commit is empty (a local build) or the build repo is not a target repo. `GitHubPulls.merge` answers the merge
commit's sha, which `MergePrDelegate` keeps as the flow variable `mergeSha` for the post-deploy check
([`docs/post-deploy-check.md`](docs/post-deploy-check.md)).

## AI accounts

Moved to [`docs/ai-accounts.md`](docs/ai-accounts.md).

## The usage meter

Moved to [`docs/the-usage-meter.md`](docs/the-usage-meter.md).

## Consumption ledger

Moved to [`docs/consumption-ledger.md`](docs/consumption-ledger.md).

## frakpanel tiles

Moved to [`docs/frakpanel-tiles.md`](docs/frakpanel-tiles.md).

## Starting a flow from an issue

Moved to [`docs/starting-a-flow-from-an-issue.md`](docs/starting-a-flow-from-an-issue.md). Sections: "Blocked by", "Authorization from an intake".

## Work items

Moved to [`docs/work-items.md`](docs/work-items.md). Sections: "The tables", "The gate", "The API", "Sources".

## First-mate sessions

Moved to [`docs/first-mate-sessions.md`](docs/first-mate-sessions.md). The
session API (`/first-mate/sessions`) takes a plain sign-in bearer or a
`first-mate` token, never a `first-mate-pod` one, so the desktop and the web
page drive First mate with the user's sign-in bearer and mint no token of
their own; `DELETE /tokens/{id}` ends the sessions started with that token
whatever its purpose, and `purpose: first-mate` remains the phone's
revocation-scoping label.

## The desktop channel and screenshots

Moved to [`docs/desktop-channel.md`](docs/desktop-channel.md). A signed-in
desktop holds `GET /desktop/channel` open (SSE, outbound only) and answers
`capture` frames with `POST /desktop/capture/{requestId}`; First mate's
`screenshot_desktop` tool on `/mcp/frakworks` (never a Smith's) asks for the
whole window or the `left`, `middle` or `right` pane, and is refused when no
desktop is connected, the desktop's setting is off, or 15 s pass.

## Push notifications

Moved to [`docs/push-notifications.md`](docs/push-notifications.md).

## Tokens

Moved to [`docs/tokens.md`](docs/tokens.md).

## Images

Moved to [`docs/images.md`](docs/images.md).

## Configuration

Moved to [`docs/configuration.md`](docs/configuration.md).

## Run it

Moved to [`docs/run-it.md`](docs/run-it.md).
<!-- test intake 2026-10-17T14:52Z: safe to delete -->

