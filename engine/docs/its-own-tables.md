# Its own tables

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

The engine keeps data of its own next to Operaton's, in the same database:
Operaton's `ACT_*` tables stay in the default schema, and the engine's live in
the `engine` schema. Flyway creates that schema and applies
`modules/app/src/main/resources/db/migration` at startup. Operaton manages its own tables
(`schema-update=true`), so no migration touches them. In the local stack the
database is Postgres (`engine-db`, password `ENGINE_DB_PASSWORD` in `.env`,
data on the `engine-pgdata` volume). Tests and a run outside Docker use
in-memory H2 (not its Postgres mode, which Operaton's H2 schema script fails
in), so migrations are plain SQL that both accept. DDL H2 cannot parse — a
Postgres partial unique index (`CREATE UNIQUE INDEX ... WHERE ...`) or a
`GENERATED ... STORED` column — goes instead in a Kotlin `JavaMigration` (a
`BaseJavaMigration` subclass in package `db.migration` under
`engine/modules/app/src/main/kotlin/db/migration/`) that runs per-database SQL chosen by
the connection's database product name, as `V36__doctor_bugs_setting.kt` does:
on H2 it gets the same "at most one row" rule from a unique index over a
generated column that is true for the marked row and null for the rest. A
migration both databases accept stays plain SQL under
`engine/modules/app/src/main/resources/db/migration`; the Kotlin form is the exception,
not the default.

| Table | What |
|---|---|
| `engine.target_repo` | The repos the engine works on, by `owner/name`, each with its engine check: `ci_check`, the check run's name and the only source of the repo's check (null: no check), and `ci_paths`, the path prefixes that need it, comma-separated (null or empty: every path); see "Chore", `wait for the engine check`. Its `security_review` (default true): false, its chores, bugs and features skip the security review ("Chore", `security reviews on?`). And its GitHub token, `token`, sealed under `ENGINE_SECRETS_KEY` with `key_id` and `token_set_at` (null: the repo has none and cannot be worked); see "Each repo's token". And its webhook secret, `webhook_secret`, sealed the same way with `webhook_secret_key_id` and `webhook_secret_set_at` (null: its deliveries are refused); see "When it scans". And its `scan_interval_minutes` (1 to 1440, no column default: `POST /repos` writes 60 unless told otherwise; V32 gave every existing row 60, the engine-wide hourly scan it replaced), how often it is scanned; see "When it scans". And its `doctor_bugs` and `engine_defects` (V36, both false by default; `engine_defects` needs `doctor_bugs`, and a unique index lets one row at most have it); see "Doctor". And its `friction_bugs` (V43, false by default, independent of the other two): the engine files two-things friction bugs in it; see "Friction notes", "Two things". And its `docs_first` (V73, false by default): its tickets default to the light docs path; see "Docs path". And its `child_mode` (V86, `serial` by default, a CHECK on `serial`, `parallel`, `live`): how a feature's split is cut and its children worked; see "Split features". |
| `engine.ai_account` | The AI accounts Smiths run on, their credentials, sealed, and an optional email note. See "AI accounts". |
| `engine.repo_access_audit` | History kept from before V54 dropped `engine.repo_access`: every per-repo grant and revoke. |
| `engine.engine_access` | The trusted logins: the one engine-wide list of GitHub logins that may authorize, answer, cancel, retry and close tickets on every target repo. |
| `engine.engine_access_audit` | Every grant, revoke and V54 `migration` of a login: when, what, and who said they asked (`flow` is null, or legacy from before V54). |
| `engine.engine_token` | The bearer tokens the engine issues (V70; "Tokens"): each bound to a `login`, with a `name`, its `prefix` (the 8 characters after `frk_`), its `hash` (hex SHA-256, unique; never the token), `created_at`, `created_by`, `expires_at` (null: never), `last_used_at` (bumped at most once a minute) and `revoked_at`; indexed on `hash` and `login`. |
| `engine.push_device` | The iPhone first mate's push devices (V79; "Push notifications"): one row per APNs `device_token`, with the `login` and `token_id` that registered it (revoking the token deletes them), `platform`, `environment` (`sandbox` or `production`), `created_at`, `updated_at`. |
| `engine.handled_issue` | Each approval (a `labeled` event) the scan acted on: started or refused, and why; `asked_by` (V47), who asked for the ticket: the trailer's `by <login>`, else the issue's GitHub author ("Intake", `by`). A row from before the column has null; on the repo's next scan, while it has such rows, the scan reads the repo's open listing once (no read per issue) and fills each row on an open issue from the listing's trailer or author (a failed listing is tried again next scan). A row on a closed issue stays null, and once every null row of a repo is on a closed one the listing is not read for them again (until a restart); there is no backfill script. `points` (V49): the trailer's story points ("Intake", `points`), null on a ticket without them. |
| `engine.deferred_issue` | Each approval waiting on an open "blocked by" issue, with what it needs to start later. See "Blocked by". |
| `engine.intake_dependency` | Each ticket an intake filed blocked by an earlier intake's issue because that intake had not filed yet; the row goes when the engine re-points it at the ticket. See "Blocked by", "An earlier intake". |
| `engine.smith_session` | One row per Smith run followed: its repo, issue, mode, model, and when it started and ended. See "Summarize: a live run summarized". |
| `engine.smith_session_line` | Every stdout line of those runs, in order, as read. No retention yet. |
| `engine.jev_call` | Every call the engine made to Jev, answered or not: use case, flow, ticket, model, request, response, latency, threshold, what the flow did with the pick, and the `outcome` it learned later. See "Jev accounts". |
| `engine.engine_setting` | Engine settings kept in the database rather than static config, one row per `name`, the value as text. The rows today are `intake.tier`, the intake refiner's own tier (seeded 5; `GET`/`PUT /intake/tier`, "Intake"), and `security.tier`, the tier every security review runs at (seeded 5 by V42; `GET`/`PUT /security/tier`, "Tier resolution"), and `jev.ssh-judge.threshold`, the SSH judge's confidence threshold (unseeded: no row is 0.8; "The judge server"). |
| `engine.hitlp_server` | The HITLP server the engine talks to (V85, #2500; "The HITLP requester", "The settings"), one row (`singleton`, checked true): `mcp_url`, the requester's MCP Streamable HTTP endpoint; `requester_token`, its bearer token sealed under `ENGINE_SECRETS_KEY` with `requester_key_id` and `requester_token_set_at`. (`surface_url` went with the engine's surface relay, V97, #100013.) Nothing is seeded: `PUT /settings/hitlp` writes it; no property or environment variable stands in for it. |
| `engine.tier` | The tier map: one row per tier 1-10 (always ten; a CHECK holds the range), its model as a short name and its effort, and when it was last set. V42 seeded it with `bots/tiers.toml`'s `[claude]` rows. `GET /tiers`, `PUT /tiers/{tier}`. See "Tier resolution". |
| `engine.research_answer` | A research ticket's answers, one row a round (`full_name`, `issue`, `round`, `process_instance`, `summary`, `md`, `html`, `answered_at`): kept by the engine, never committed unless the asker says `save`, served by `GET /ticket/{owner}/{repo}/{n}/pitch` after the ticket closes too. See "Research". |
| `engine.saved_chat` | First mate chats saved for later from the desktop, one row per conversation keyed by the UUID the desktop minted: title, started and saved times, repo, tier, Claude session id, the saving host, turn and message counts, and the transcript as a JSON array (at most 200 messages, unredacted). See "Saved First mate chats". |
| `engine.doctor_position` | Each running ticket flow's last position as the doctor saw it, since when it has been there, and when it was last seen; rows of ended flows go on the next tick. See "Doctor". |
| `engine.doctor_run` | One row per doctor run: the ticket, the position it was stuck at and since when, and the diagnosis, remedy, target and outcome. `GET /doctor/history`. See "Doctor". |
| `engine.doctor_sighting` | One row per doctor run on a ticket that was really stuck (every outcome but `healthy`): the run, the not-held retry before it, the ticket and root, `kind`, run class, `where`, summary, signature, and the bug, lumping (`exact`, `jev`, `new` or `human`) and the `doctor-sighting` Jev call. V35. See "Doctor". |
| `engine.doctor_signature` | Each distinct sighting signature: `where`, leaves, the normalised incident lines, first seen, and its bug. See "Doctor". |
| `engine.friction_group` | A group of friction notes one change would have spared (#1094, V39): `id`, `title` (the founder's summary line, cut to 240) and `created_at`; since V43 (#1112) its two-things bug, `bug_repo`, `bug_issue`, `bug_state` (`filing`, `open`, `closed`; null while none) and `bug_intake_id`. `GET /friction/groups`. See "Friction notes". |
| `engine.friction_signature` | Each friction note's exact key the first time it was seen, the group it points at, the normalised key `line` it hashes and `first_seen`; a key is added to a group when its founder founds it and when Jev joins a note with a new key. See "Friction notes". |
| `engine.doctor_bug` | The bugs doctor sightings feed: repo, issue (null while its intake refines), intake, state (`filing`, `open`, `needs-repro`, `fixed`, `declined`), the bug it regresses, created and closed; the repeat step taken (`repeat_level`), the reproduces restarted, when it got `needs-review`, and when its fix started running. `GET /doctor/bugs`. See "Doctor". |
| `engine.intake_notification` | One row per intake whose ask named a `notifySession` and that closed with a filed ticket: the session, repo, ticket and url, and once the host-side watcher ran `claude -p`, when, by whom and what it printed. Unique per intake. `GET /intake/notifications` lists the undelivered ones. See "Intake", "Telling the session that filed it". |
| `engine.friction_note` | One row per friction note a Smith left (#876): `note_friction` during a run (`source` `as-you-go`) or an item of the retro after it (`retro`), with what the engine stamped (repo, issue, the smith-run's `run_id`, its root flow's `process_instance_id`, `noted_at`) and what the Smith gave (`kind`, `summary`, `evidence`, `severity`); or one First mate left (#968, `source` `first-mate`), stamped with the caller's `login` (V33; null on every Smith and retro row) and the `repo` it named, with no issue, run or flow. Since V39 (#1094) also the note's exact key (`signature`, written in the insert, NOT NULL), and what the friction grouper writes afterwards: `group_id`, `grouped_by` (`new`, `exact`, `jev`, `fallback`, `no-jev`, `user`), `jev_call_id`, `group_attempts` and `next_attempt_at`; since V41 (#1098) `grouped_login`, the login of a user's pull-out; since V43 (#1112) `two_things` (Jev's `noul` when it marked the note as two things, null when unmarked) and `two_things_call_id`. Read by `read_friction_notes`, and by `GET /friction/notes`, `GET /friction/groups` and `GET /friction/summary` (#1046, the desktop's Analytics > Friction notes). See "Friction notes", "The frakworks server", "The frakworks server in a Smith", "The retro". |
| `engine.job` | The custom jobs (V46, "Custom jobs and the SDK", "The job registrar"): `id`, `name`, `kind` (`prompt`, `bean`, `flow`, `refactor`), `owner` (V55: `user`, or `system` for a repo's refactoring job), the target `repo` (cascades with it), `config` and `signal` as JSON text, `created_by`, `created_at`, `next_run_at` (null unless the signal is `every`, or `at` before its run), `last_run_at` and `last_instance`. `GET /jobs`. |
| `engine.repo_mcp_server` | One row per MCP server per target repo that a First mate for it, a Smith run of it, or both get (V65, "First mate's MCP servers"; V81 `audience`, #2449): `repo`, `name`, `kind` (`http`, `sse`, `stdio`), `audience` (`smith`, `first-mate`, `both`; rows from before V81 are `first-mate`), `url`, `command`, `args` (JSON list), `headers` and `env` (JSON, secret values sealed), `updated_at`, `updated_by`. `/repos/{owner}/{name}/mcp-servers`. |
| `engine.refactor_config` | One row per target repo that set its refactoring up (V55, "Refactoring jobs"): `enabled`, `schedule_seconds` (at least 60), `values_text` (null: the shipped `bots/refactor-values.md` is in force), `max_tickets` (1 to 20), `max_lines` (10 to 5000), `approval` (`human` or `none`), `job_id` (its system job, null while off), `tier` (V57: the refactor-scan ticket's tier, 1 to 10; null files no scan), `updated_at` and `updated_by`. `GET`/`PUT /repos/{owner}/{name}/refactor`. |
| `engine.intake_question` | Each question a refiner asked on an intake, when, and the answer, who gave it and when; unanswered rows are what `GET /questions` lists. A question still waiting when its flow ends (superseded, deleted, finished) closes on the next read, answered `closed: flow ended (<reason>)` by `engine`. |

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" localhost:8090/repos                                  # list
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPOST localhost:8090/repos -H 'content-type: application/json' -d '{"repo":"acme/widgets"}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPATCH localhost:8090/repos/acme/widgets -H 'content-type: application/json' -d '{"ciCheck":"build","ciPaths":["src/"]}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XDELETE localhost:8090/repos/acme/widgets
```

Adding a repo that is already there answers 200 instead of 201. Removing
one with a flow still running on it answers 409 naming each flow (its process
key and issue) and removes nothing: cancel or finish them first. The
database is the source of truth for who may do what (decided with the user,
2026-09-23: no config file); the audit table is its history.

## A new target repo's GitHub setup

What a repo needs on GitHub before the fleet can work it. `GET /repos/{owner}/{name}/setup` answers every item it can with the repo's token and
writes nothing; the rest stay a manual list.

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" localhost:8090/repos/acme/widgets/setup               # the check
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPOST localhost:8090/repos/acme/widgets/setup/labels # create the missing labels
```

The desktop's Config > Repos does not call `GET …/setup`: it asks with
`POST /repos/setup/check` (`repos/RepoSetupChecksController.kt`), which answers 202 at
once, runs the same check on a virtual thread, and delivers its result as
`setup` frames on the event stream ("Events"): `started`, then `done` with the
report or `failed` with GitHub's error. The engine keeps the last report per
repo in memory, with its `checkedAt`: a request for a repo whose report is
younger than 5 minutes (`RepoSetupChecks.FRESH_FOR`) re-publishes it as one
`done` and reads nothing from GitHub, and a request while a check of the repo
runs joins it rather than starting another. `"force": true` (the row's manual
re-check) is the one way past the 5 minutes. A failure is not kept, so the
next request reads again; an engine restart forgets every report, so the
first request after it reads GitHub.

- [ ] **A token that reaches the repo** ("Each repo's token"): the repo's own,
      `PUT /repos/{owner}/{name}/token`. Its user has write
      access to the repo (collaborator or org member). A fine-grained PAT lists
      the repo, with Contents, Issues, Pull requests and Actions read/write,
      and Workflows read/write if tickets may touch `.github/workflows/`
      ("The token's scopes"). The check: `hasToken`, `readable` and `push`.
- [ ] **Labels**: `authorized`, `chore`, `bug`, `feature`, `feature:auto`,
      `feature:confirm`, `proposal`, `intake`. The check: `missingLabels`
      (compared ignoring case, as GitHub does); `POST …/setup/labels` creates
      them, under the `repo` scope the engine already labels issues with.
- [ ] **Webhook** to the engine's `POST /hooks/github` ("When it scans"):
      repo Settings → Webhooks, payload URL the Funnel's public
      `https://<host>.<tailnet>.ts.net/hooks/github`, content type
      `application/json`, a secret of this repo's own (set the same value on
      its row: `PUT /repos/{owner}/{name}/webhook-secret`, or **Set webhook
      secret** in the desktop's Config > Repos, whose Generate mints one),
      events "Issues", "Check suites", "Issue comments", "Pull requests" and
      "Pushes", all five: the first two wake the scan, with the next two they
      are the stream's `ticket` frames ("Events"), which the desktop app
      updates its views from, and "Pushes" is the stream's `push` frame, which
      First mate's fresh clones of the repo follow (part 2 of #973); a repo whose hook sends fewer gets no `ticket` or `push` frames.
      ("Pull request reviews" wakes too, if you like.) The Funnel on the engine's host fronts that one path:
      `tailscale funnel --set-path /hooks/github http://127.0.0.1:8090/hooks/github`.
      It must front that path and **nothing else on the engine's port**: the
      rest of the engine's API is unauthenticated, and only the signature
      check guards `/hooks/github`. Without the webhook the repo still works
      on its scan interval ("When it scans", 60 minutes unless set), so a label waits up to that long.
      The check: `webhook.status`
      is `present` when one active hook takes all five events (or every event,
      `*`), `missing` when none does (a hook with "Issues" and "Check suites"
      alone, or the four without "Pushes", is `missing`), and `unknown` when GitHub refuses to list hooks (403/404: listing
      needs admin on the repo); then check it by hand. The check shows each
      hook's events and whether it is active, never its payload URL: the
      endpoint is unauthenticated, and a hook URL can carry a secret.
- [ ] **Branch protection / rulesets on the default branch**: the engine
      squash-merges with the repo's token after its own reviews. Required
      *reviews* would block that merge (a Smith's review is not a GitHub
      approval), so either require none or let the fleet user bypass. Squash
      merging must be allowed. The check: `defaultBranch.requiresReviews`
      (a ruleset's `pull_request` rule or the classic protection; null when
      the branch is protected, reading its protection needs admin, and no
      ruleset says) and `defaultBranch.forbidsSquash` (the repo's
      `allow_squash_merge`, or a ruleset whose merge methods leave out
      squash). A bypass is not read, so a `true` with a bypass is fine.
      A repo with no commits yet has no default branch: the check answers
      `defaultBranch.exists: false` (not protected, the rest null) and no
      `ciCheck`, as there is nothing to look at. Pushing a first commit is the fix.
- [ ] **Required checks**: if the repo requires a status check, name the same
      check as the row's `ci_check` so the engine waits for it before
      reviewing. A required check the engine does not wait on turns up as a
      merge refusal (an incident) instead. The check: `ciCheck.ranOnDefaultBranch`,
      whether the row's check has a run on any of the default branch's newest
      30 commits (one check-runs call a commit, stopping at the first run).
      A check that runs only on pull requests shows `false` there.
- [ ] **Trusted logins**: the list holds at least the user's own login
      (`POST /access`, "Starting a flow from an issue").
- [ ] **Actions runners**, if the check needs self-hosted ones (frakworks'
      `engine` check runs on the `build` runners).
