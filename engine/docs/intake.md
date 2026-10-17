# Intake

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

The refiner chooses an unprescribed ticket's tier from `bots/model-choice.md` and the engine's tier map, which a refine run's brief carries as `tiers` (`[{tier, model, effort}]`, read as the run starts; "Tier resolution") and `run-smith.sh` renders into the refine prompt; Jev then decides the kind, feature mode and tier the user left open, with the refiner's choice as the fallback ("Chore or feature", below).

**An intake's ask is a work item, never a GitHub issue** (#100002). The
first step, `open the ask item` (`OpenAskItemDelegate`), opens the ask as a
work item of kind `ask` (source `ask`, ref the intake id; engine/docs/work-items.md,
"Asks as work items") in the intake's repo, by the submitter. Its ticket number
(100000 and up) is the intake's `issue` variable, so everything the intake
writes "on its issue" (a comment, the refiner's question, an answer, a
follow-up, the close) goes to the item through `Tickets` (the store): a comment is a
`conversation` entry by `engine`, a close moves it to `done` (`cancelled` for
not planned). Nothing about an intake is written to GitHub. `File ticket` refines
that same item in place into the filed ticket.

**`POST /intake` answers the ticket at once** (#100086). `open the ask item`
has no `asyncBefore`, so it runs inside the request's correlation: the 202
answers `{intakeId, repo, ticket, ticketUrl}`, read from the started instance's
`repo`, `issue` and `issueUrl`, and `submit_ask` passes them on. Since the
refiner rewrites the item in place, `ticket` is the filed ticket's number; only
a refine result with another `repo` files elsewhere and closes the ask item
with a link there. GitHub could not give this: its issue existed only once the
refiner filed it.

**The ask item's title is a summary of the change** (#1936).
`OpenAskItemDelegate` asks `TitleSummarizer` (engine-accounts) for one
before it opens the item: one Anthropic Messages call on `claude-haiku-4-5`
(`max_tokens` 40, 10 s timeout, `engine.anthropic.upstream`) on the account
`AiAccounts.peek` names for anthropic, so the Smiths' rotation is unaffected,
asking for a title of at most 70 characters naming the change a user would see,
not the implementation. The ask goes without a leading `Tier:` line. The answer
is cut to one line, its quotes, `Title:` label and trailing punctuation dropped,
and the title is `Intake: ` plus it, at most 80 characters, cut at a word
boundary with `…`, never mid-word. With no usable account, an HTTP error, a
timeout or an empty answer, the title falls back to `Intake: ` plus the ask's
first non-empty line (a leading `Tier:` line skipped), cut the same way, and
the engine logs `intake <id>: title summary unavailable (<why>), using the ask's
first line` at `warn`: an intake never fails for want of a title. The
`Intake: ` prefix stays for the board's sake; nothing reads it (the item's
kind, `ask`, is what the board and the desktop go by). The
refine prompt tells the refiner the same for the filed ticket's title, which
`fileTicket` writes over the ask item's: name the change the user would
see, not the implementation (`bots/smith.md`, "Intake tickets").

**An ask has no length limit** (#1238). `ask` and `duplicateQuestion` are
stored as bytes variables (`Brief.textValue`, in `ACT_GE_BYTEARRAY`), not as
Strings, whose `TEXT_` column is `varchar(4000)` and failed `POST /intake` with
a 500 for a longer ask. Every reader goes through `Brief.text`, which also takes
the String an intake started before the change carries; `GET /intake/{id}`
answers both as text, as before.

**Intake → ticket links: `engine.intake_ticket`** (#1482). Which ask item an
intake opened and which ticket it filed is application data, one row per intake
(the instance id as key; repo, the ask item's ticket number in `issue`, its url, ticket, `created_at`,
`filed_at`), not Operaton variables. `OpenAskItemDelegate` writes the row
when it opens the ask item (rows from before #100002 name the GitHub issue intakes opened then), and `fileTicket` / `fileUnlabeledTicket` set
its `ticket` when they file.

**The file step is idempotent** (#1802; `FileOnce`). One ask files one ticket
however many times `fileTicket` or `fileUnlabeledTicket` runs: the job that
runs it may roll back after the create reached GitHub (a later write in the
same job failing, a failed commit), and Operaton retries it from before the
create, which once filed two tickets for one ask (#1773 and #1775). So the
create is bracketed by two writes to the intake's row, each in a transaction
of its own (`REQUIRES_NEW`) that the job's rollback leaves standing:
`filing_at` before the call (V63), `ticket` right after it. A run first
reuses a recorded `ticket`, with no GitHub call; with `filing_at` set and no
ticket (the create's answer lost) it lists the repo's open issues and reuses
the one whose trailer names this intake (`<!-- engine: intake <id> …`),
recording it; only with neither does it file. `task-start-work` and
`task-close-intake` are `asyncBefore` in `bpmn/intake.bpmn`: the file step's
transaction commits before the start, and a failure in the start, the close
or the notify retries from that step, never from the file step. Every reader goes through `IntakeTickets`:
`IntakeRefs` (resolve, references, `filedBy` for `GET /issues`, one query per
repo), the board's intake flow lookup by issue url, `IntakeAmender` and
`FrictionIntakes`. The `issue`, `issueUrl` and `ticket` variables are still set
for the flow itself, but nothing outside it reads them, except
`IntakeTicketBackfill`: at startup it copies the links of intakes started
before the table from history, skipping any intake already in it, so a second
run writes nothing; an intake whose history has no issue has no link to copy.

**Conversation context.** `POST /intake` (and the `submit_ask` tool) takes an
optional `context` next to `ask`: what the filing session wrote about the
conversation that led to the ask. It is the bytes variable `context`, set only
when non-blank. The ask item keeps the ask as a verbatim quote and adds a
`### Conversation context` section after it, labelled as the session's, not
the user's words; the refiner's brief ticket is that body, and `bots/smith.md`
"Intake tickets" says the quote binds over it.


**A follow-up to an intake** (#1173). First mate's `amend_intake` (or
`IntakeAmender.amend`) records the user's follow-up as an answered
`engine.intake_question` row, question `Follow-up from @<login>`, so the
intake's `answers` carry it from then on (answers are ordered by when they were
answered), and records `@<login> added: …` on the ask item. Then, by where
the intake is:

- **A refine run in flight** (`call-refine-ask` active): its sandbox is stopped,
  and a process-instance modification cancels `call-refine-ask` and starts it
  again on the same intake instance, so the new run's brief has the follow-up.
  `refining: "restarted"`.
- **At `answer the smith's question` or `answer: is it a duplicate?`**: recorded
  only; the question still waits, and the next refine round carries the
  follow-up with the answer. `refining: "waits on a question"`.
- **Before the refine** (waiting for `authorized`, or at the duplicate check):
  recorded only; the first refine run carries it. `refining: "recorded"`.
- **Filed** (`task-file-ticket` or `task-file-unlabeled` finished, or about to
  run) or **not running**: refused, nothing recorded. A filed intake answers
  `intake <id> has already filed #<n>; use amend_ticket on #<n>` (while filing,
  `try amend_ticket on the ticket in a moment`): the follow-up goes to
  `amend_ticket` on `#<n>`, also when the intake was refined in place into it,
  not to a comment, which the running flow never reads.

**A follow-up to a ticket** (#1362). Once an intake has filed its ticket, a
late addition goes on the ticket: First mate's `amend_ticket({ticket, repo,
text})`, or `POST /ticket/{owner}/{repo}/{n}/amend` with `{"text"}` (403
from a Smith sandbox), both `TicketAmender.amend`. The token's login must be trusted,
and `repo` is always named. On the ticket's root flow (`FlowCanceller.running`),
the engine side first:

- the amendment `{by, at, text}` is appended to the flow's `amendments`
  variable, which every later brief carries as `amendments`;
- the flow's ticket snapshot (`approvedTicket`) gets an `## Amendments`
  section, created on the first amendment, one entry `- @<login>, <ISO
  instant>: <text>` per amendment, so every later run (work, security, review,
  verify, accept, replan) judges against the amended ticket. The rest of the
  body is kept as it was approved: a `Tier:` line, a `Builds:` line and the
  trailer read the same;
- then, by where the flow is:

| Flow is at | What happens | `work` |
|---|---|---|
| `call-do-work` | its sandbox stopped, `call-do-work` cancelled and started again on the same instance | `restarted` |
| `call-security-review`, `call-review-pr`, `call-ci-check` | that activity cancelled (its sandbox stopped if it has one), `call-do-work` started | `restarted` |
| a bug's `call-reproduce` | its sandbox stopped, `call-reproduce` cancelled and started again | `restarted` |
| a feature's `call-design` | its sandbox stopped, `call-design` cancelled and started again | `restarted` |
| a user task `user-task-answer-%` | recorded only; the question still waits, and the round after the answer carries it | `waits on a question` |
| a split feature's parent (at `child closed` or `call-replan`) | recorded on the parent only; no child is touched; the next replan's brief carries it. To amend a child, amend the child's own ticket | `next replan` |
| any other step (a service task in flight: `task-open-pr`, `task-rebase-pr`, `task-merge-pr`, `task-apply-replan`, ...) | refused 409 `ticket <repo>#<n> is between steps; try again in a moment` | — |

The new work round continues on the existing branch and PR, as a rework round
after `changes requested` does; `open/update pr` comments `Round <n>` on the PR.
Nothing closes the PR or the issue, and no instance is deleted. A cancelled
`call-ci-check` leaves nothing behind: the next `open/update pr` clears the
check's variables before it waits again.

Then GitHub: the issue's body is written from the amended snapshot (its
`## Amendments` section; an edit made on GitHub to the body after authorization
is overwritten by it), and a comment `@<login> added:\n\n<text>\n\n—
engine, <next>`, `<next>` one of `the work runs again with it`, `the question
still waits; the next round reads it`, `the next replan reads it`. A GitHub
write that fails is logged and listed in `failedWrites`; it never undoes the
loop-back.

The result is `{ok: true, repo, ticket, work, lost, failedWrites}`. **Lost
commits**: a Smith's commits are bundled only when its run ends
(`smith/run-smith.sh`), so a stopped `work` or `reproduce` run's unpushed
commits are lost, and `lost` says so (`the stopped work run's unpushed
commits`); null otherwise. What was pushed, the PR and its branch, is kept.

Refused, recording nothing: blank `text` or one over `MAX_ANSWER_CHARS` (400),
no `repo` (400, naming the target repos), a repo that is not a target repo
(404), a login that is not trusted (`@<login> is not a trusted login`, 403), an
intake that has not filed its ticket yet (`<repo>#<n> is an intake; use
amend_intake`, 400; an intake refined in place into its ticket has filed, a
`FILING` step finished in its history, and that ticket is amended here like
any other), and a ticket
with no flow running or whose `task-merge-pr` finished (a bug at `verify on
main`, a feature at `check acceptance`): `ticket <repo>#<n> is merged/closed;
file a follow-up` (409).

A possible later optimisation for long work runs, not built: hand the
amendment to the running Smith session instead of stopping it. A `PostToolUse`
hook returning `hookSpecificOutput.additionalContext` was shown on 2026-10-06 to
reach a running `claude -p` session; the run would read the amendment at its
next tool call and keep its unpushed commits.

**The refiner's own tier** is a setting in the engine's database
(`engine.engine_setting`, row `intake.tier`, "Its own tables"), not static
config: every `refine` run resolves its model and effort from it at the moment
the run starts (`StartSmithDelegate`, through `IntakeTier`), never from the
ask's own `Tier:` line, which is for the ticket the refiner files.

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" localhost:8090/intake/tier                   # {"tier":5,"model":"claude-opus-5-5","effort":"medium"}
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPUT localhost:8090/intake/tier -H 'content-type: application/json' -d '{"tier":8}'
```

`PUT` takes a JSON integer 1-10, a tier of the map;
anything else (a string, a fraction, a tier outside 1-10, another key) is a
400 and the stored tier stays. The desktop sets it from Config > AI Accounts,
in its own section above the account tiles (`observability/desktop/README.md`).
Migration `V21__engine_setting.sql` seeds it with 5 (opus medium), the value
the old Spring property held: the refiner's judgement sets every ticket's
kind, tier and scope, so it runs at tier 5. The intent is tier 8 (fable low)
once Fable capacity allows; the per-account Fable limit (#166) makes that
unsafe today. That later move is a `PUT`, not a deploy.

**Chore or feature.** The refiner files a `chore` when the ask states what to
change and how to check it, even when that is a multi-file UI or behaviour
change: one work run, a review, a merge. It reserves `feature` for an ask
whose design is genuinely open: several defensible approaches, or unclear scope
(until #583, also acceptance the user must judge; a Smith now checks
acceptance in both modes). A kind or feature mode the user names in
the ask binds. Decided with the user 2026-09-25 (#272), after #253, a UI
change with a clear spec, was filed as a feature, split in two, and cost
about 20 Smith runs for two runs of work.

**Jev decides the kind, the feature mode and the tier** (#502). The refiner still drafts the
ticket and says what it would file; then `read result` asks Jev (use case
`refine-pick`, `RefinePicker`) in one call, with the ask and the drafted
title and body as state (each cut to 24,000 characters): a `choice` of kind
(`chore`, `feature`, `bug`, `proposal`, described as the refine prompt
describes them), a `choice` of feature mode (`auto`, `confirm`), and a
`score` over the ten tiers of the engine's tier map, each level its
model and effort, with `bots/model-choice.md` "The rules" trimmed into the
question. A tier is the score's most probable level (else its score
rounded). The engine files at each pick whose confidence is at
`engine.jev.refine-floor` (0.8) or above; below it, or with no answer (Jev
unreachable, over its limit, no jev account), that field is the refiner's
choice, and with no answer at all the ticket is filed exactly as before.
A feature Jev made of another kind takes Jev's mode when sure, else
`confirm`; a kind that is not a feature carries no mode.

- **What the user named binds.** The ask's `Tier: <n>` line (CLAUDE.md,
  "Filing tickets") binds the tier, and the refiner lists in its result's
  `userNamed` which of `kind`, `featureMode`, `tier` and `points` the user named in
  the ask or its answers. Jev is not asked about those; it still decides the
  rest, and when nothing is left open no call is made. A refiner's
  `research` or `incident` is outside Jev's choice and stands.
- **The pick is on the ticket.** When Jev answered, the filed body ends with
  a line saying what Jev picked, with its confidence, what the refiner
  chose, and what was filed, so the user can relabel (or put a `Tier:` line
  in the body) before authorizing. The trailer names the call:
  `<!-- engine: intake <id> · tier <n> · <priority> · by <login> · jev <call id> -->`.
- **`by`: who asked.** Every ticket the engine files carries `· by <login>`
  right after the priority (before a `jev` or `triage` mark): the intake's
  `submittedBy` (`doctor` or `friction` for a bug the engine's doctor or
  friction filer asked for), or for a split's child and a proposal's build
  the parent flow's asker. It is optional: a ticket from before it, and a
  hand-filed issue, has none, and every parser (`RefinePickOutcome.callOf`,
  `ProcessFlowStarter.parseTierFromBody`, the doctor's priority step,
  `TicketFiler.isTriage`) reads a trailer with or without it;
  `TicketFiler.askedByOf` reads it. Where a flow is claimed
  (`engine.handled_issue`, "Its own tables") the row's `asked_by` is the
  trailer's `by`, else the issue's GitHub author, and the flow's start puts
  the same value on it as the `askedBy` variable. The board (`askedBy` on
  `GET /issues` and `GET /ticket`) and "What needs you" (`for`) read it.
- **`points`: the story points.** The refine result's `ticket.points`, one
  of 1, 2, 3, 5, 8, 13, or 21, 34, 55 for a feature only (`bots/story-points.md`), is
  required to file (`RefineResult.problems`, which also refuses points above 13
  on any other kind); the user's `Points: <n>` line binds, and the
  refiner lists `points` in `userNamed` (Jev never picks points). The trailer
  carries it right after the tier:
  `<!-- engine: intake <id> · tier <n> · points <p> · <priority> · by <login> -->`,
  read by `TicketFiler.pointsOf`. Where a flow is claimed the row's `points`
  (V49) is the trailer's, and `GET /ticket` returns it as `points` (null for a
  ticket without one: a triage or hand-filed issue, a proposal's build, one from
  before it). A split's child carries the design's points for it the same way
  ("Split features"). Points are recorded and shown only; nothing schedules or picks
  a tier by them.
- **The audit.** The call's `engine.jev_call` row holds the picks and
  confidences (its response), the floor (`threshold`), and as `action` what
  was filed per field and from whose choice, e.g. `filed: kind feature
  (jev), mode confirm (jev), tier 2 (jev 5<floor)` (kept within the
  column's 100 characters; a failed audit write does not undo the pick). When
  a flow starts on the ticket (the intake's carry-over or a later
  start by a trusted login), `ProcessFlowStarter` records what it started as as the
  call's `outcome` (`RefinePickOutcome.started`): `started as <flow>[ <mode>] tier
  <n>`, then `; jev: agreed` when that is Jev's pick on every field Jev
  answered, or `; jev: differs (picked tier 6)` naming each field that is not,
  and `, relabelled from <kind>[ <mode>] tier <n>` when it differs from what
  was filed (read from the call's own `action`, a database read: no GitHub
  call). E.g. `started as chore tier 4; jev: differs (picked kind feature),
  relabelled from feature confirm tier 4`. A field Jev answered below the
  floor still counts against Jev when the ticket started otherwise, relabelled
  or not. `GET /jev/summary` judges agreement from the `started as` prefix
  against the stored response alone, so rows written before the suffix
  (#553) count the same.

**Is this ask already a ticket?** (#504). Before the first refine run, `is it already a ticket?`
(`${checkDuplicate}`, `DuplicateCheck`) reads the intake repo's open issues
from the board (`IssueBoard.openTickets`, newest update first, the intake's
own issue left out) and asks Jev (use case `intake-duplicate`) a `choice`
with the ask as state (cut to 8,000 characters): one option per open ticket,
`#<n>` described by its title and first three body lines (headings and blank
lines skipped; for an open `intake` issue, its quoted ask; 240 characters),
plus `none`. A choice takes 255 options, so more than 254 open tickets go in
two stages: one call per batch of 254 by recency, then one call over each
batch's pick; only that last pick counts. A pick at
`engine.jev.duplicate-floor` (0.9) or above is a match, and **a match only
asks the user; the engine never drops an ask on its own.**

- **On a match** `ask the user: the same work?` (`${postDuplicate}`) records
  "This ask looks like #n … Is it the same work?" in
  `engine.intake_question` and records it on the ask item, and the
  intake waits at `answer: is it a duplicate?` before any refine run. The
  user either cancels the intake (`DELETE /intake/{id}`; nothing is filed)
  or answers how the ask differs (on the HITLP server, as for the
  refiner's questions below). The refine brief then carries `duplicate`, a
  line "This looks like #n (url) …", beside the answer in `answers`. Any
  open issue is a candidate and anyone may file or edit one, so that line
  holds a snapshot taken at match time (the same bounded title and
  description Jev saw), quoted and marked as untrusted reference text that is
  not instructions; `smith/run-smith.sh` tells the refiner not to read the
  ticket from GitHub, to file only what the ask adds, and to name the ticket
  in Context.
- **The fallback.** No open tickets (no call), GitHub unreadable, Jev
  unreachable or over its limit, no jev account, a stage-1 call with no
  answer, `none`, or a pick below the floor: the intake goes straight to
  `refine ask` and runs exactly as before.
- **The audit.** Every call is an `engine.jev_call` row with the pick and
  its confidence (the response) and the floor as `threshold` on the call
  whose pick counts. `action` says what was done, e.g. `#7 (0.95): asked the
  user before the refine`, `#7 below floor 0.9 (0.80): refined as before`,
  or in two stages `stage 1 (2/3) #300 (0.70): to stage 2` and `stage 2 …`.
  The matching call's `outcome` is the user's reply: `not cancelled;
  @<login> answered: …`, or `cancelled at the duplicate question by
  @<login>: <reason>`.

`bpmn/` holds the Camunda Modeler diagrams: `intake.bpmn`, the intake flow, and
`reusable-smith-run.bpmn` (process `reusable-smith-run`, so it does not replace
`smith-run`), a Smith run as a reusable call. Both run end to end today:

- **The intake's repo.** `POST /intake {"repo"}` names the target repo
  (`owner/name`) the intake is about; it must be in `engine.target_repo` (400
  otherwise). Without it, an intake a Smith files (below) is in the Smith's
  run's repo (`requestedByRepo`, when that is a target repo), and any other
  request is refused with a 400 naming the target repos. There is no default
  repo: the `engine.intake.repo` property (`ENGINE_INTAKE_REPO`, `TICKETS_REPO`
  in `.env`) was removed on 2026-10-04 at the user's word, since every call has
  a repo context. The
  POST sets the instance's `repo`, and everything after uses it: the ask
  item, `File ticket`, the refining Smith's clone, and every access check
  (the submitter's trusted login, `authorize`, a start of the ask item, an
  answer). An ask about a private repo stays out of the public frakworks
  tracker.
- `task-open-ask` (`${openAskItem}`) opens the ask as its `ask` work item in
  the intake's `repo` (above; an instance started without one fails here, an
  incident), titled `Intake: <summary>`, with no GitHub call. It sets `repo`,
  `issue` (the item's ticket number), `issueUrl` and `askItem` (its id). It also decides
  whether the intake is authorized (below): `authorizedBy` is the submitter
  (`submittedBy` in the POST) when they are a trusted login there, else
  null, and it says which on the item. The POST's `authorize` overrides
  that: `false` leaves `authorizedBy` null even when the submitter may, and
  `true` is checked by `POST /intake` before any process starts (below).
  An intake the doctor starts in-engine sets `authorizedByEngine` (`doctor`,
  which `POST /intake` never sets): it is authorized by the engine's doctor,
  with no trusted login ("Doctor").
- `authorized?` goes straight on for an authorized intake; otherwise the
  intake waits at `ask item started` (`event-intake-authorized`) until a trusted login
  starts its ask item: `POST /work-items/{id}/start` on the item, or First mate's
  `approve` on its ticket number. Both go through `IssueApprovals.approveIntake`, which
  correlates `intake authorized` with that login as `authorizedBy` and records
  `Authorized by @<login>` on the item. No label is read or written: a label on
  GitHub never starts an intake.
- `is it already a ticket?` (`${checkDuplicate}`) asks Jev whether the ask
  (not a doctor's: its signature already lumped it, "Doctor") is an open ticket ("Is this ask already a ticket?", above). On a match,
  `ask the user: the same work?` and `answer: is it a duplicate?` wait for
  the user, and `record the answer` (`${recordDuplicateAnswer}`) keeps the
  answer as the call's outcome before `refine ask`; otherwise it goes
  straight on. A refine run again after an answered refiner question does
  not repeat the check.
- `refine ask` calls `reusable-smith-run` with `mode=refine`, passing all
  variables in and out. That calls `smith-run` (above), so the refiner is a
  real Smith in a sibling container. It judges the ask by `bots/smith.md`
  "Intake tickets" and writes its answer to `result.json` (below). **It
  writes nothing to GitHub**; the engine does every write. It works from the
  brief's `ticket`, the ask as opened (or as it was when a trusted login started it), not the live item and
  its entries: what it files may start a chore or a bug.
- `read result` (`${readResult}`) checks that answer against the engine's
  rules (`RefineResult.problems`: a known outcome, a ticket with title and
  body, tier 1–10, priority `p2`–`p5`, no push to `main`) and sets `outcome`.
  A failed run, a missing file or a broken rule stops the intake there as an
  incident, before any GitHub write. The push-to-main check
  (`grantsPushToMain`) only flags an Allowed-scope line that affirmatively
  *grants* access ("May push to main", "Push to main" or "Pushes to main",
  followed by a truthy tail); a denial such as "May not push to main: no"
  passes. To retry an intake an earlier round rejected this way: see
  "Incidents" below — retrying now reruns the Smith rather than replaying the
  same stored (still-rejected) result.
  For a `file`, it then asks Jev for the kind, feature mode and tier ("Jev
  decides the kind, the feature mode and the tier", above) and keeps what
  the intake files at as `refinePick`; Jev failing never fails the run.
- `File ticket` (`${fileTicket}`) files the drafted ticket as an engine work
  item at `refinePick` (Jev's pick or the refiner's choice, per field;
  `engine/docs/work-items.md`, "Asks as work items"). Nothing is written to
  GitHub. It refines the intake's own ask item in place, so its
  ticket number is the intake's `issue`. It sets `workItem`, `ticket`, `ticketUrl`, `kind` and, for a
  feature, `featureMode`.
- **Filing in another repo.** A refine result may name `repo` (`owner/name`) for `file` and
  `insufficient-permissions`: the target repo the work belongs in, when that is not the intake's (an
  intake in codefrak-oss/frakworks whose work is in codefrak-oss/frakpanel). `read result`
  (`RefineResultHandler`) refuses a `repo` that is no registered target repo, naming the target repos
  (there is no default repo), and checks the `workflow` scope against that repo's token. Absent, or the
  intake's own repo, `File ticket` refines the ask item in place as above. Another repo gets a **new work
  item** there (a second item of kind `ask` with the same source ref, refined at once), recorded with
  `engine.intake_ticket.ticket_repo` (V71, null for the intake's own repo);
  `File unlabeled ticket for human triage` files its ticket there. Both set `ticketRepo` (the target
  repo's own spelling; every intake sets it), which `start … if authorized`, `say what was filed` and the
  session notification read. `blockedBy` `#n` that is no intake is an issue in the ticket's repo; an
  earlier intake whose ticket is in another repo is held, as before. `say what was filed` closes the
  intake's ask item with `Done. Filed <owner/name>#<n>`. `IntakeRefs` resolves the intake to `ticketRepo` and
  the ticket, so a later intake naming it is blocked by the ticket in that repo, and `filedBy(repo)` lists
  it under the repo it is in. A ticket an earlier dependency row waited on in another repo is told to
  wait on it by hand: no blocker is written across repos.
- `a chore, bug or feature?` sends any of them, and a `proposal`, to `start chore/bug/feature
  if authorized` (`${startFlowFromIntake}`): "Authorization from an intake", below.
- `close the ask as not planned` (`${closeWithdrawn}`), reached by the `withdrawn` outcome: the ask or the
  user's answer to the refiner's question withdrew it ("drop it", "#2685 already covers this"). The refiner
  gives a `reason` quoting the answer and no `ticket`, `kind`, `repo` or `blockedBy`. The step quotes the reason in
  the ask item's conversation, closes the ask item as not planned (`cancelled`), publishes the intake as `ended`
  with no ticket and goes straight to `intake closed`: nothing is filed, nothing starts, no session is told,
  and no `/needs` row is left. Before it, a refiner told to drop an ask could only say `insufficient-permissions`,
  which filed a `· triage` ticket.
- `File unlabeled ticket for human triage` (`${fileUnlabeledTicket}`), reached by the `insufficient-permissions`
  ("can't do with current permissions") outcome (the flow is labelled "insufficient permissions"), files
  the refiner's drafted ticket in the intake's repo as a work item of kind **`triage`** (V100): no work kind and
  no `authorized`, so neither the scan nor the intake starts a flow on it, `GET /needs` lists it by its kind
  (`insufficient-permissions`) and publish never sends it to GitHub (`flow-unlabeled-close` goes
  straight to `say what was filed`). Before the engine trailer the body gets a
  `## Needs human triage` section: the refiner's `blocker` (when it gave one), its `reason`, and the
  kind it would have filed it as. The trailer still carries `intake <id>`, the tier and the priority,
  and ends with `· triage`. It sets `ticket` and `ticketUrl` as `File ticket` does (no `kind`), so
  the intake closes with `Done. Filed #<n>`, ends at `intake closed` with no incident, and
  `GET /intake/{id}` and `IntakeRefs` report it as `filed` (a later intake naming it is blocked by
  that ticket). A retry with `ticket` already set files nothing. The ticket is listed under "What
  needs you" as `insufficient-permissions` until the user labels it with a kind or closes it.
  The refiner's `reason` (required) says why, and its optional `blocker`
  says it in 20 words or fewer (#443; results from before it have none,
  and the rules never ask for it). The ticket timeline serves both as
  `ticket.blocked` ("Ticket timeline", below), which the desktop ticket
  view shows as a "Blocked: can't do with current permissions" banner; the
  intake keeps its refine result, so the banner still shows once it closed.
- `say what was filed` (`task-close-intake`, `${closeIntake}`) records on the
  ask item that the ask above was refined into this `<kind>` and whether its flow
  started, and leaves it open: it is the ticket. Where `ticket` differs from
  `issue` (an unlabeled triage ticket, a ticket in another repo) it records `Done. Filed #<ticket>` and closes the
  ask item.
- The `ask` outcome: `post the smith's question` (`${postQuestion}`) records
  the question in `engine.intake_question` and records it on the ask
  item as the Smith wrote it (prose, then its `ask` block, so a reader of
  the thread sees the same row the desktop app would), then the intake waits
  at the user task `answer the smith's question`. The question is filed on
  the HITLP server as a `human.ask` ("The HITLP requester", below), and the
  comment ends with the hint where to decide it; the decision record answers
  it as an answer would, naming its decider (`HitlpAnswers`). Only a trusted login decides it,
  because the answer is the next Smith's instructions as much as the ask was.
  The engine records the answer,
  comments it on the issue, and `refine ask` runs again with the whole
  exchange in the brief as `answers` (oldest first; `smith/run-smith.sh`
  tells the Smith each answer binds like the ask). A refiner may ask again
  about something the answers leave open, and the loop repeats. Nothing
  reads the issue thread: an answer written as a GitHub comment does nothing.
- `notify the session that filed it` (`${notifyFilingSession}`), last: see
  "Telling the session that filed it" below.
- `dryRun: true` runs the container without Claude; it writes a canned
  `result.json` with `fakeOutcome` (default `file`). Every GitHub write still
  happens for real.

## Telling the session that filed it

Intake is fire-and-forget: a Claude Code session that posts an ask with
`submit_ask` replies "submitted" and moves on, and the ticket number only shows
up later on GitHub, in `GET /intake/<id>` or in the desktop. `notifySession`
closes that loop (#610): the session names itself, and once the ticket is
filed the engine has the fact pushed back into that very session, which then
says, unprompted, "That ticket we created was [#928](../…)".

**The mechanism** (verified 2026-09-26 on Claude Code 2.1.283;
https://code.claude.com/docs/en/cross-session-messaging). Every interactive
Claude Code session registers itself in `~/.claude/sessions/<pid>.json` with a
`name` (stable when started with `claude -n <name>` or renamed with
`/rename`; otherwise derived, like `frakworks-c2`) and a messaging socket. A
headless `claude -p` session has the `SendMessage` and `ListAgents` tools and
can message a local session **by that name**; the full session id is refused
("No agent named … is reachable"), so the name is the address. The receiver
sees `› Message from @engine: …` and, if idle, starts a turn on it; with
`"crossSessionInbound": "accept"` in its settings the message is not held
for approval.

**Who does what.**

1. `POST /intake {"notifySession": "<name>"}`: the caller names the session
   (its own name from `ListAgents`, "This session is <name> [<ref>]"). It is
   the process variable `notifySession`, so `GET /intake/<id>` shows it.
   Absent, nothing below happens. The `/intake` skill sent it until it was
   removed on 2026-10-10; `submit_ask` does not take it yet, so no session
   sets it today.
2. `notify the session that filed it` (`NotifyFilingSessionDelegate`), the
   step after `say what was filed`, queues one
   `engine.intake_notification` row (intake, session, repo, ticket, url)
   when the intake filed a real ticket, with its kind (`file`) or unlabeled for
   human triage (`insufficient-permissions`): not on a dry run, and never from the
   `ask` outcome, which does not reach it. The row
   is unique per intake, so a retried step queues nothing twice. The step
   never fails the intake: an error is logged and the intake still ends at
   `intake closed`.
3. **A host-side watcher delivers**, `scripts/intake-notify-watcher.py`. The
   engine runs in Docker and has none of what sending needs: the sessions'
   sockets under `/tmp/cc-socks`, a `claude` install, an account to run it
   as. A pull was chosen over a relay the engine would POST to because it
   needs no listener on the Mac, no port or token the container must know,
   and the engine stays the source of truth on what was delivered. It polls
   `GET /intake/notifications` (every 5 s), runs, per row,

   ```
   claude -p --model haiku --no-session-persistence -n engine \
     "Use the SendMessage tool to send the following message, verbatim and nothing else,
      to the local Claude Code session named <session>. … <message>"
   ```

   with a 120 s timeout, and reports `POST /intake/notifications/<id>/delivered`
   `{"by": <host>, "result": "sent: <stdout, its msg_id line>"}` or
   `"failed: <exit code, output, or the timeout>"`. A failure is recorded
   and not retried (the fact is on GitHub and in `GET /intake/<id>` anyway);
   a second watcher's report is a 409 and it does nothing more. The message
   is `IntakeNotification.message`: "Intake <id> was refined and filed as
   ticket #<n> in <owner/repo>: <url>. Tell the user, in one line, that the
   ticket you created is #<n>, with #<n> a markdown link to that url."
4. The receiving session answers with one line, the linked ticket number,
   nothing else, and does not act on the ticket (the message says so).

**Running the watcher** on the machine with the sessions, from the repo root:

```
python3 scripts/intake-notify-watcher.py            # ENGINE_URL, default http://localhost:8090
python3 scripts/intake-notify-watcher.py --once     # one poll (a cron, a test)
```

It sends `FRAKWORKS_TOKEN` (an engine token, "Tokens") as its bearer token: the
engine refuses its polls and reports without one.

To keep it running, a launchd agent (`~/Library/LaunchAgents/com.frakworks.intake-notify.plist`,
`ProgramArguments` = `python3`, the script's absolute path; `KeepAlive` true;
`EnvironmentVariables` with a `PATH` that has `claude` on it), loaded with
`launchctl load -w`. Its tests: `python3 -m unittest discover -s scripts/tests`.
The session-side settings: start the session with `claude -n first-mate` (any
stable name; the skill sends whatever the name is) and set
`"crossSessionInbound": "accept"` in `~/.claude/settings.json`, or the message
waits on an approval dialog.

## Tier resolution

Which model and effort a Smith runs at comes from the ticket's tier through
the engine's tier map (`TierTable`): table `engine.tier`, ten rows, each a
tier 1-10's model (a short name: `haiku`, `sonnet`, `opus`, `fable`) and
effort, and `engine.engine_setting`'s `security.tier`. Every resolution reads
the database, so a write (`PUT /tiers/{tier}`, `PUT /security/tier`, the
desktop's Config > frakworks > Tiers) applies to the next run that resolves
a tier, with no redeploy. There are always exactly ten tiers: no endpoint
adds or removes one, and the engine refuses to start when a row is missing,
naming `V42__tier.sql`, which seeds them.

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" localhost:8090/tiers                         # [{"tier":1,"model":"claude-haiku-4-5-20251001","effort":"low"}, ...]
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPUT localhost:8090/tiers/6 -H 'content-type: application/json' -d '{"model":"opus","effort":"high"}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPUT localhost:8090/security/tier -H 'content-type: application/json' -d '{"tier":5}'
```

The map was the checked-in `bots/tiers.toml` until 2026-10-05 (#1099), read
by the engine, copied into the Smith image and bundled into the desktop app;
V42 carried its `[claude]` rows and `security_tier` (5) over, and the file and
every reader of it were deleted. Its `[codex]` column was not carried over:
nothing resolved it. Found
2026-09-25: until this was wired, every engine run used the sandbox default,
`claude-sonnet-5` at medium, whatever the ticket said.

- A flow started from an intake takes the tier the intake filed at: Jev's
  pick at or above `engine.jev.refine-floor`, else the refiner's
  `ticket.tier` ("Intake", "Jev decides the kind, the feature mode and the
  tier"); one started
  by an `authorized` label takes the `Tier: <n>` line in the body, or the
  `<!-- engine: intake … · tier <n> … -->` trailer the file step leaves. No
  tier anywhere: the default, with one warning naming the ticket, never a
  refusal.
- The resolved `model`/`effort`/`tier` are set on the flow instance, so every
  Smith run in it (design, work, PR review, acceptance) inherits the tier. The
  tier, not the model, is what the ticket keeps: `start smith` resolves the
  flow's `tier` against the map as it is at each run (and a retried
  `start smith` incident too), writing the result back as `model`/`effort`,
  so moving a tier to another model reaches flows already running (#2511).
  Only a flow started with a manual model pin and no tier keeps its
  `model`/`effort` as started. Two
  modes ignore them: `security` always runs at the map's security tier
  (`security.tier`, seeded 5, `GET`/`PUT /security/tier`), `check` (the
  post-deployment check, "Smith runs", "The check mode") at the map's check
  tier (`check.tier`, seeded 4 by V95, `GET`/`PUT /check/tier`),
  and `refine` (the intake's own Smith) at the refiner tier stored in the
  database (seeded 5, `GET`/`PUT /intake/tier`, "Intake"), read as the run starts;
  the `Tier:` first line of an ask is for the ticket it files, not the refiner.
- Each run logs `run <id>: tier <n> -> <model> <effort>`, and `tier` is a
  process variable, so the history and the desktop say what a run cost.

## Structured output

A Smith's product for the engine is a file, not its log: it writes
`/work/out/result.json` (`OUTPUT_PATH`), and `collect result` in `smith-run`
copies it out of the exited container into the bytes variable `output`
(bytes, because a String variable stops at 4000 characters). It is the same
for every Smith run; what goes in the file is up to the caller. The
intake's schema is `RefineResult`:

```json
{"outcome": "file|insufficient-permissions|ask|withdrawn",
 "kind": "feature|bug|chore|proposal|research|incident",
 "featureMode": "auto|confirm (a feature only)",
 "userNamed": ["kind", "featureMode", "tier"],
 "ticket": {"title": "…", "body": "markdown", "tier": 2, "priority": "p3"},
 "reason": "insufficient-permissions and withdrawn only", "blocker": "insufficient-permissions only, optional: ≤20 words",
 "question": "ask only"}
```

## Smith results

Every mode that writes `result.json` (refine, work, reproduce, verify, design,
replan, accept, review, security, propose, fix, diagnose) has one JSON Schema,
`smith/schemas/<mode>.schema.json`, and that file is the contract:

- The engine packs the directory into its jar as `result-schemas/` and
  `readResult` (`ReadResultDelegate`) checks the result against it before
  parsing. A result that fails is an incident naming each field, e.g.
  `the smith's result breaks the rules: smith/schemas/reproduce.schema.json:
  /testable: string found, boolean expected`; retrying it reruns the Smith,
  as for any other broken rule. The mode's own rules (`problems()`: what
  each outcome needs) run after it.
- The Smith image copies the same files to `/etc/smith/schemas` and has
  `check-result <mode> [file]` (`smith/check-result.py`, on Debian's
  `python3-jsonschema`, no network). Every result prompt in
  `smith/run-smith.sh` states each field's JSON type and has the Smith run
  `check-result` until it prints `ok`, so a Smith fixes its own file before
  it exits. `check-result propose` also lints a `drafted` result's pitch page
  (its `pitch`, under `$SMITH_WORK/repo`, or a third argument)
  against its `options`, by a port of `PitchRules` with the same messages
  (#1742): the schema alone cannot tell that `options` should have been there.
  `check-result design` and `check-result replan` also refuse a child whose
  `dependsOn` names itself or a later child, with `ChildDraft.problems`'
  message (#2412); a replan numbers its children after the brief's
  `split.filed`.

A field the engine or a BPMN decides on is strictly typed: `outcome` (an
enum per mode), `testable`, `kind`, `featureMode`, `met`, a question's
`confidence` and `impact`, and the fields the engine files or reads as they
are (a ticket's or child's `title` and `body`, `tier`, `priority`, a PR
`title`, a finding id). A field it only passes on to a brief or a GitHub
comment (`summary`, `evidence`, `rootCause`, `repro`, `untestableWhy`,
`question`, `reason`, `how`, `why`, a finding's `ask`, the plan's text) is
marked `@PassAlong` in the result class and takes any non-empty JSON: a
string reads as itself, a number or boolean as its JSON, and an object or
array as an indented, fenced JSON block, so a comment shows it readably. The
stored result keeps the JSON as the Smith wrote it, so a brief carries an
object as an object (#235: a reproduce run's `evidence` was an object, and
the parse failed on every retry).

**The Allowed scope is guidance.** The engine does not check a work run's
bundle against the ticket's `## Allowed scope`. The section tells the work
Smith where the change belongs (its brief says to stay inside it where it
can, and to name in its summary any file outside it the goal needed), and
the review run judges what was changed against the ticket. The work scope
fence (#753) and its `rescope`
push-back (#829) did check it, and were removed on 2026-10-04: every
push-back had ended in a widened scope, and the fence read the refiner's
prose wrongly often enough to park tickets on incidents. The engine still
reads the section before a ticket is filed, for a grant of push to main and
for a `.github/workflows/` path the token cannot push (`AllowedScope`,
"The token's scopes"). There a line that keeps paths out grants nothing:
one that opens with "May not", "Must not", "Do not", "Never", "Not", "No",
"Nothing", "Out of scope", "Off limits", "Excluded", "Except" or "Forbidden",
the lines nested under it, and the bullets under such a lead-in that ends in
a colon; and a granting line is read only up to "except", "excluding", "but
not" or "other than".

The log stays for humans. The Smith still holds its repo's token for reading
issues; a read-only token is the next step (a work Smith no longer needs to
push, and its pushes are denied).

```bash
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -s -X POST localhost:8090/intake -H 'content-type: application/json' -d '{"ask":"make a thing","dryRun":true}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -s localhost:8090/intake/<intakeId>          # ended: true moments after the Smith exits (its die event)
```
