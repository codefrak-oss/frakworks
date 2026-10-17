# Starting a flow from an issue

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

An issue carries two kinds of label (decided with the user, 2026-09-23):

- a **kind**, named after its flow (`chore`, `bug`, `feature`, `proposal`; an intake has no
  label at all, its ask is an engine work item: "Authorization from an intake"). Anyone
  who can label may set it (on GitHub, triage access and up); it only says
  what the work is. A `feature` also carries its mode, `feature:auto` or
  `feature:confirm` ("Feature"), which the approval fixes like the kind;
- **`authorized`**, the approval. Applied by a trusted login, it starts the kind's flow.

Filing or classifying an issue starts nothing: on a public repo anyone can
file one, and its text becomes a Smith's instructions.

The **trusted logins** (`engine.engine_access`, `Access`) are one engine-wide
list of GitHub logins: a login on it may authorize, answer, cancel, retry and
close on every target repo, the ones added later too. There are no per-repo or
per-kind grants. No property or environment variable adds anyone. V54 carried
every login of the old per-repo and engine-wide grants (not `jobs`) into the
list, each with a `migration` audit entry.

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPOST localhost:8090/access -H 'content-type: application/json' \
  -d '{"login":"lundmatt1"}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" localhost:8090/access                                 # logins, history
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XDELETE localhost:8090/access/lundmatt1
```

Every call, these included, acts as the bearer token's login ("Tokens"). The
first token is minted through the bootstrap door: while no unrevoked token
exists, `POST /tokens` needs none (`curl -XPOST localhost:8090/tokens -H
'content-type: application/json' -d '{"login":"lundmatt1","name":"first"}'`, for
a login already on the list; on an empty list insert the login into
`engine.engine_access` over `psql` first, as `POST /access` needs a token). From
then on every request carries `Authorization: Bearer <token>`, and `POST /access`
adds the rest.

**The scan** (`IssueScanner`) looks at every open issue labelled
`authorized` in each target repo while the trusted list is not empty. The approval is the
issue's latest `labeled` event of `authorized`, and it is acted on once
(`engine.handled_issue`, keyed by the event), whatever runs the scan:

- the issue must have had **exactly one kind** label when it was
  authorized (replayed from its label events); that kind is the flow;
- the approval's **actor** must be a trusted login (logins compare
  case-insensitively); the author does not matter, so a stranger's issue can
  be authorized;
- the issue is read (the **snapshot**: title, body, author) and must not
  have **changed after the approval**: a kind label added or removed, a
  `renamed` event, or a body `lastEditedAt` (GraphQL; REST does not say)
  after it. What was approved has to be what runs;
- then the flow starts with the snapshot as its `approvedTicket`, and every
  Smith of that flow works from the snapshot (`ticket` in the brief), never
  from the live issue or its comments;
- for a chore or a bug, no earlier run of that flow on the issue may
  **still count**: one that is still active (parked on an incident
  included), or that opened a PR. A run that ended or was cancelled without
  a PR changed nothing on GitHub, so a new approval runs it again; one that
  opened a PR does not, so two runs never push to the same `smith/issue-<n>`;
- a refusal is recorded and commented on the issue once. Removing and
  re-adding `authorized` is a new approval, the way to run it after an edit.
  Removing it from a running flow does not stop the flow.

**A ticket the engine started itself** (label event 0: an intake's carry-over,
a refactoring job with approval `none`) is skipped at its first `authorized`
label, with no row and no comment, whoever's login the label is under, trusted
or not (#1440, widened in #1802). That label is the visible record of the
engine's start, not an approval: refused, it showed as a red `refused` row over
a running flow, as "not a trusted login", "no kind label" (GitHub may list
`authorized` before the kind label written a moment earlier) or "still
running". The claim is committed before the label is written
(`AuthorizedStart`'s `label`, after the filing job's commit), so the scan the
label's webhook wakes always finds it. A later `authorized`, removed and added
again by a person, is an approval like any other.

A start that fails (GitHub or the engine down) gives its claim back, and the
next scan tries again.

**When it scans.** No polling loop, with a slow backstop:

- at **startup**, for whatever happened while the engine was down;
- on each **wake**: GitHub delivers a webhook to the engine's own
  `POST /hooks/github` (`GitHubWebhookController`). **Each target repo has
  its own webhook secret**, sealed on its `engine.target_repo` row like its
  token (`webhook_secret`; `PUT`/`DELETE /repos/{owner}/{name}/webhook-secret`,
  the desktop's Config > Repos). There is no engine-wide secret: the
  `engine.webhook.secret` property (`WEBHOOK_SECRET` in `.env`) was removed on
  2026-10-04 at the user's word, a webhook being a thing of one repo. The
  engine reads the payload's `repository.full_name`, unverified, only to pick
  whose secret checks the delivery; one that names no repo (an
  organisation-level hook's `ping`), a repo that is no target repo, or a repo
  with no secret is 401.
  The engine's host publishes that one path through the Tailscale Funnel
  (`tailscale funnel --set-path /hooks/github http://127.0.0.1:8090/hooks/github`)
  and nothing else: the rest of the API is unauthenticated, so the Funnel
  must never front the engine's port as a whole. Before anything but that
  repo name is read, the delivery's `X-Hub-Signature-256` is checked: `sha256=` and the
  hex HMAC-SHA256 of the raw body under that repo's secret, compared in constant
  time. A missing or wrong one is 401 and wakes nothing; a body over 1 MiB
  is refused (413) without being read in full. A signed delivery is 204 with
  an empty body whatever it carries, and wakes only when its event
  (`X-GitHub-Event`) and the payload's top-level `action` are allowlisted:
  `issues` `labeled`, `unlabeled`, `reopened` or `closed`; `pull_request`
  `opened`, `synchronize` or `closed`; `pull_request_review` `submitted`;
  `check_suite` `completed`; `ping`. After the signature is verified, and
  only then, two more fields are read for the stream ("Events"):
  `issue.number` (or `pull_request.number`) and, for a label event,
  `label.name`; an `issues`, `issue_comment` or `pull_request` delivery is
  published as one `ticket` frame (`action` as GitHub names it, `commented`,
  or `pr` with `prNumber`), whether or not it wakes: an `issue_comment`
  delivery publishes and wakes nothing. Nothing else in the payload is read,
  and it is never stored or logged. A wake says only that something changed
  somewhere, so it scans every target repo, off the request thread. A label
  added to an issue is an `issues` `labeled` delivery, but only from a repo
  that has the webhook: **each target repo needs its own** ("A new target
  repo's GitHub setup"); adding a target repo here does not create it. The
  engine wakes at most once a second, and a delivery inside that window
  wakes nothing, which loses nothing: the one scan reads everything. Each
  wake scans twice, at once and `engine.wake.follow-up` (15 s) later:
  GitHub's labelled-issues listing lags a label by a few seconds (the first
  live run, 2026-09-24, found nothing on a scan right after labelling and the
  issue on one a moment later), so the first scan can miss it. When the
  follow-up acts on something, the engine logs `the follow-up scan … missed`;
  if that line never appears, the second scan can go;
- when a **work flow ends with its own close** (`task-close-ticket` or
  `task-close-nothing-to-do` in a chore, bug or feature): after that
  transaction commits, `CloseWake` runs the resume pass for the split
  parents waiting on that issue alone (`IssueScanner.wakeWaitingOn`, the
  same checks as a scan). A feature child closes its issue at the
  merge but ends only after its acceptance check (a pre-#583 confirm child,
  at the user's acceptance), which is engine-internal: no webhook announces
  it (#275). Ordinary deferred work still waits for a
  scan;
- **on each repo's scan interval** (`scan_interval_minutes` on its
  `engine.target_repo` row, 1 to 1440 minutes; 60 unless `POST /repos` or
  `PATCH /repos/{owner}/{name}` set another, and the desktop's Config > Repos
  sets it). A one-minute tick scans the repos whose interval has passed since
  their last scan, and only those; any scan that covered a repo (a wake,
  `POST /scan`, startup) restarts its interval, and a failed one is retried on
  the interval, not on the next tick. For a repo with its webhook this is the
  backstop: GitHub does not retry a failed delivery, so one lost in a repo that
  then goes quiet would otherwise wait for the next event there, and an hour
  is enough. A repo with no webhook, or with no webhook secret (its deliveries
  are refused), is seen only on this scan, so a shorter interval is what
  makes it prompt. Each scan costs GitHub requests from the repo's token
  (`GET /repos` says how many, `scanCallsPerScan`): an issue listing, a read
  of each authorized issue, a re-check of each deferred one and a poll of
  each flow waiting on its check. GitHub allows a token 5,000 an hour, shared
  by every repo on that token and by the Smith runs using it; the desktop
  warns over 10% of it and asks again over 25%. The intervals live in memory:
  a restart scans every repo at startup and counts from there;
- on `POST /scan`, by hand.

After each of these scans (both of a wake's) comes the engine-check pass,
`CiCheckWaits`: it reads the check run on the pushed head of every flow
waiting at `wait for the engine check` and wakes the ones that completed;
`POST /scan` lists them as `ci-check` `checked` actions. A `check_suite`
completed delivery is a wake like any other, so a check
usually wakes its flow within a minute; the repo's interval scan catches a lost one (it polls only the repos it scanned).

`engine.scan.enabled=false` turns off the startup and interval scans and their
check passes (the tests do); it leaves `POST /hooks/github`'s wakes on.

## Blocked by

Before either start path (the scan's label, and an intake's carry-over,
below) starts a chore, a bug or a feature, it reads the ticket's **blockers**:
its work item's store dependencies (`TicketReads.blockers`: each blocker
`open`, or `closed` with `completed` or `not_planned`). The relation is
written by the engine (a split's parts, and a ticket an intake filed: "An
earlier intake", below); the deferral keeps only which tickets are waiting.

- **An open blocker defers the approval** instead of acting on it, a third
  outcome beside started and refused. The refusal checks above come first
  (a refused approval is refused, not deferred). Nothing is claimed in
  `engine.handled_issue`, so the approval stays unhandled, and a row goes
  into `engine.deferred_issue`: repo, issue, the approving label event (0
  for an intake carry-over), who authorized it, the flow, a feature's mode,
  the approved snapshot, the refiner's tier and the carry-over's detail for
  an intake, the blockers as last read, which of them were already reported
  closed without a merge, first seen and last checked. The ticket gets one
  comment, `Waiting on #n (open). Starts on its own when every blocker is
  closed by a merged PR.`, naming the lowest-numbered open blocker.
- **Every scan re-reads each waiting row's blockers** (the same scans as
  above; there is no polling of its own). Once every blocker is closed and
  **each close was the engine's own merge**, the flow starts from the stored
  approval and snapshot through the same claim-and-start the label path
  uses, the row goes, and the ticket gets `Started: #n closed.` A merge-close
  is a blocker whose `engine.handled_issue` start is a flow that ended through
  its `task-close-ticket` step (the "Done. Merged" close chore, bug and
  feature share) or its `task-close-nothing-to-do` step (the work found
  nothing to do; "Chore"); a run cancelled after opening its PR got to neither.
- **A blocker closed any other way** (by hand, or a cancelled run) does not
  start it: the ticket gets `Blocker #n was closed without a merge;
  re-authorize to proceed` once for that blocker, and the row stays. When
  the blocker is an ask item the comment says which intake and why:
  that it ended without filing a ticket (and how: cancelled by whom, or, for an
  intake still parked at the incident from before #730, the refiner could not file it), that it filed `#T` (block on `#T` to wait for
  it), or that it has not filed a ticket yet.
  Re-authorizing (remove and re-add `authorized`) with no blocker open
  starts it at once and drops the row.
- **A fresh `authorized` on a waiting issue replaces the stored approval**
  (event, actor, snapshot) on the same row: no second row, no refusal, and
  no second waiting comment.
- **A waiting approval can still be withdrawn**, as one could before
  deferral existed. Before a waiting row starts, the scan reads the live
  issue: it must still be open, and for a label approval its latest
  `authorized` must still be the stored event, not taken off since, with no
  kind label changed after it. Otherwise the row is dropped without starting
  (the scan reports it as ignored), and a new approval starts over. An intake
  carry-over has no label to check; its ticket must still be open. The
  stored actor must also still be trusted, or the start is refused as
  usual.

`GET /issues` shows each issue's `blockedBy` (`[{number, state}]`, empty for
none) and `deferred`. A waiting issue's `flow` has outcome `deferred` and
detail `waiting on #n`. A row with `waiting_instance` set is a split feature
parked inside its running flow ("Split features"): the scan wakes that
instance instead of starting one, the board shows the running flow with step
`waiting on #n`, and only "still open" is checked before it wakes.

### An earlier intake

An ask can depend on an earlier ask that has no ticket yet: the user or
First mate names it as "Blocked by intake `<intake id>`" or by its intake
issue `#N`. An intake rewrites its issue in place into the ticket it files
(one issue per ask), so `#N` is that
ticket; the engine resolves the intake to the ticket it filed (`IntakeRefs`, from the intake
instance's history: an intake id, or `#N` matched to the intake whose
`issue` it is, newest first).

- **At refine**, the brief's `intakes` lists each earlier intake the ask
  and its answers name, with its `state`: `filed` (with `ticket`),
  `refining`, or `ended` (with `ended`, how), and its `ask` text (cut to
  `IntakeRefs.ASK_CHARS`, 2 000 characters, plus `…`). A refiner reads a
  `refining` sibling's ask from it rather than asking the user what the
  sibling asked for. The refiner writes `#T` for a filed one, and lists what the ticket waits on in the result's
  `blockedBy` (`#n` or an intake id; `refine.schema.json`).
- **At filing** (`fileTicket`), each `blockedBy` entry becomes GitHub's
  native relation on the new ticket: a filed intake's ticket; a plain
  issue as is; a still-refining intake's issue `#N`, which becomes its
  ticket in place, with no `engine.intake_dependency` row. An entry that
  resolves to the ticket's own number is dropped with a comment on it. An intake that ended without filing is
  nothing to wait on: it is not written, the ticket gets a comment naming
  it, how it ended, and that it was ignored, and it does not hold the
  carry-over, which goes on as with no blocker (#717). What does hold it:
  an entry naming no intake the engine knows, an intake in another repo,
  or a relation the engine could not write. Each is said on the ticket and
  sets `dependencyHeld`, and the carry-over does not start: the comment
  says it starts when someone with access labels it `authorized`.
- **When that intake files**, the same step re-points each waiting row
  left from before one issue per ask whose `#N` differs from the ticket
  (a row naming the ticket itself is only dropped):
  block the dependent ticket on `#T`, remove `#N` (`Tickets.unblock`, a store
  dependency removed), comment `Blocked by #T, the ticket intake #N
  filed.`, and drop the row. A write that fails there is said on the ticket
  for a human to fix, and the row goes anyway.

These writes are caught rather than failing the step as an incident: the
step has already filed the ticket, and a retry would file it twice.

## Authorization from an intake

Decided with the user, 2026-09-23: an intake's authorization carries to the
chore or bug it turns into, so an authorized ask does not need a second
approval. Extended 2026-09-25 from chores alone to bugs as well.

- **An intake is authorized** by its submitter, when `submittedBy` in
  `POST /intake` is a trusted login, or else by a trusted login's start of its
  ask item (an engine work item of kind `ask`, never a GitHub issue):
  `POST /work-items/{id}/start` or First mate's `approve`, both through
  `IssueApprovals.approveIntake`. No label authorizes an intake. Until the API is
  authenticated, `submittedBy` is taken as given. `authorize` in the POST
  makes it explicit: absent, the above; `true`, authorize now as
  `submittedBy` or refuse the POST (400 without a `submittedBy`, 403 if they
  are not a trusted login) and start nothing; `false`,
  never at filing, so it waits for a start. First mate's `submit_ask`
  always sends `true` (observability/desktop/README.md). An intake a Smith
  files is never authorized at filing ("The frakworks server in a Smith").
- **It carries to a chore, a bug, a feature, a proposal or a research** (`ProcessFlowStarter.FROM_INTAKE`)
  only if the authorizing login is trusted. Then the flow starts as the ticket
  is filed, recorded in `engine.handled_issue` (label event 0) and said on
  the ticket (`authorized by @x via intake #n`), which is the ask item
  itself, refined in place. The first `authorized` label
  on it is skipped by the scan, with no row and no comment, whoever's login it
  is under ("Starting a flow from an issue"); one removed and added again is
  refused while the flow runs, as any approval would be. A feature runs in the mode the
  refiner chose ("Two modes, and every feature runs in one of them"), which
  is also its `feature:<mode>` label. A ticket with an
  open blocker is deferred instead ("Blocked by") and started by a later
  scan as label event 0. A named earlier intake that ended without filing
  does not hold it: it is ignored with a note on the ticket, and the
  carry-over goes on as with no blocker. It is held, and the ticket waits
  for its own `authorized`, only by a `blockedBy` entry naming no intake
  the engine knows, an intake in another repo, or a "blocked by" relation
  the engine could not write ("An earlier intake"). Otherwise the ticket waits for its own `authorized`:
  the rewrite drops `intake` and `authorized`, and `GET /needs` lists it as needing authorization at once. Every other kind (`incident`) always waits for its own `authorized`; there is no flow to
  carry to.
- **Nothing in between is read from GitHub.** The refiner works from the
  intake as authorized, and the chore or bug from the ticket as the engine
  filed it (`filedTicket`), so no edit or comment after the approval reaches
  either. What it does is what the refiner drafted from the approved ask;
  the approval trusts that step, which is the point of it.
