# Chore

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

`bpmn/chore.bpmn` (process `chore`) is the flow for a ticket of kind
`chore`, started by a `chore` label on an issue in a target repo ("Starting
a flow from an issue", below) or by hand with `POST /chore`:

    do the work → open/update pr → engine check? → security reviews on? → security review → passed? → review pr → approve or changes? ─approve→ bring pr up to date ─current→ merge pr → close ticket
      ▲ ▲ ▲ ▲       ▲                   │                                                      │                           │                             │    ▲                   │
      │ │ │ └─ check failed / conflict ─┘                                                      │                           │                             │    └─────── 405 ───────┘
      │ │ └───────────────────────── findings ─────────────────────────────────────────────────┘                           │                             │
      │ └──────────────────────────────────── changes requested ───────────────────────────────────────────────────────────┘                             │
      │             └──────────────────────────────────────────────────────────── merged cleanly: pushed as a round ─────────────────────────────────────┤
      └────────────────────────────────────────────────────────────────────── conflict (at most 2 rounds) ───────────────────────────────────────────────┘

- `do the work` is a Smith run in `work` mode. It commits on
  `smith/issue-<n>` and leaves `result.json` and `commits.bundle` (a git
  bundle of its new commits) in `/work/out`. It never pushes, opens a PR or
  comments.
- `open/update pr` (`${openPr}`) comes straight after the work and pushes
  the bundle with the git CLI in the engine image (`GitPush`: a blobless
  clone, the bundle fetched, pushed; fast-forward only, `smith/` branches
  only). The bundle's `refs/heads/<branch>` is fetched into the branch, or,
  when the bundle has one head under another name (a `<base>..HEAD` bundle
  records `HEAD`), that head; several heads with none of them the branch are
  refused at verify as a broken result. `run-smith.sh` removes any
  `commits.bundle` the Smith wrote itself before bundling, so a run with no
  commits leaves none. **Which pushes trigger what** (#100043): a work run may
  push its commits so far mid-run through the frakworks server's `push_chunk`
  (`mcp/PushChunkTool.kt`; `docs/smith-runs.md`, "The frakworks server in a
  Smith"), the same `GitPush.push` to `smith/issue-<n>`, fast-forward only.
  That push is outside every flow step: it sets no variable, waits for no
  engine check (`.github/workflows/engine.yml` runs on `pull_request` only, so a
  chunk on a branch with no PR starts no Actions run), starts no security
  review or review run, and opens or updates no PR; it only publishes a `push`
  event (`docs/events.md`) a live parent's `await_children` wakes on. The done
  push here is the one that triggers the rest, once per round: the bundle from
  `START` fast-forwards over the chunks (a result whose bundle does not contain
  the pushed head is refused as a rewrite), and the check, the security review,
  the PR and the review follow it as below. Round 1 opens the PR
  (`Closes #<n>`); later rounds comment `Round <n>: …` on it. It sets
  `branch`, `pr`, `prUrl` and `round` (pushes so far), and for the next step
  `headSha` (the pushed head) and `ciCheck`, the check the branch waits for,
  and `securityReview`, the repo row's `security_review`, read at every push
  so a toggle reaches a running flow at its next round, never mid-round.
  It keeps `bundle` (and a catch-up's `merge`) for the security review; the
  next work run replaces them. A push the remote rejects (`! [remote
  rejected]`: a token scope, branch protection) is an incident after the
  first try, with no retries ("Incidents", below). Any other failure (a
  transient GitHub or git error: a `ResourceAccessException` on the
  default-branch GET, a 502 on the push) gets three tries 30 s apart
  (`operaton:failedJobRetryTimeCycle` `R3/PT30S` on `task-open-pr` in all four
  flows) before it is an incident; Operaton's default of three immediate
  retries burned all three in 30 ms. Retrying the incident restores that
  cycle, so a transient error on the retried attempt gets the same three
  tries 30 s apart. `task-rebase-pr` ("bring pr up to date", which
  reads the default branch from GitHub) carries `R5/PT15M` in the same four
  flows: five tries 15 min apart span a GitHub rate-limit window (a 403
  `API rate limit exceeded` clears when the hour resets), where the default
  left an incident within a minute. Before round 1 opens the
  PR it reads the open PRs on `smith/issue-<n>`: a retry after an earlier try
  opened the PR (and rolled back before `pr` was stored) adopts the one whose
  body carries this flow's `<!-- engine: <flow> <processInstanceId> -->`
  marker rather than opening a second, and GitHub's 422 "already exists" is
  answered the same way. An open PR on the head without that marker (a
  person's, or an earlier flow's) is not adopted: the job fails naming it,
  and is the incident once its retries run out.
- `wait for the engine check` (call activity `call-ci-check`, subprocess
  `bpmn/reusable-ci-check.bpmn`, shared by all three flows) holds both
  reviews until the repo's build check has run on the pushed head (why:
  "Why the engine check runs before the reviews", below). A passing check on
  the `engineDefects` repo has its log read for `FLAKY-TEST:` lines, each
  filed as a flaky-test ticket (no new step: `readCiCheck` does it;
  engine/README.md, "Flaky tests"). It waits only when
  the repo has a check and the branch changes a path under the check's
  prefixes. The repo's check is its `engine.target_repo` row's `ci_check` and
  `ci_paths` (set by `POST /repos` or `PATCH /repos/{owner}/{name}`, what the
  desktop edits), and nothing else: a row whose `ci_check` is null has no
  check. An empty or null `ci_paths` means every path needs the check.
  `engine.ci-check.enabled=false` (`ENGINE_CI_CHECK_ENABLED`, default true)
  turns every check off: the switch for a runner outage (Smiths still run the
  test classes they change in the sandbox, "Smith runs", above). The check is `.github/workflows/engine.yml`: the build stage
  of `engine/Dockerfile` on the self-hosted `build` runners' persistent
  `frakworks` builder, whose Gradle cache mount and layers it shares with the
  image builds ("Images"), so the suite runs there and not in a 3 GiB Smith
  sandbox. Since 2026-10-05 the same job also runs the desktop's `npm test`
  (in a `node:24` container), and works out from the pull request's diff which
  of the two it needs: the engine build for a change under the engine's
  paths, the desktop tests for a change under `observability/desktop/` or
  `engine/` (they read the BPMN files and this README). It stays one job
  because the engine reads one check run's log for the finding; this repo's
  `ci_paths` lists `observability/desktop/` so a desktop-only PR waits for it
  too. **`mergeable?` comes first** (`CiCheckConflictDelegate`,
  `task-ci-conflict`): GitHub runs a pull request's check on the merge
  commit it makes from the branch and the default branch, and makes no run
  at all for a PR it cannot merge, so a branch that conflicts with the
  default branch at the push would wait for a check that never starts, until
  the timeout's incident (PR #529 on 2026-09-26: three PRs merged after its
  branch was cut). Before the wait, the engine merges the default branch
  into the pushed branch in its own bare clone (`GitPush.mergeDefault`, the
  rebase step's merge, "Bringing the PR up to date"), pushing nothing: a
  conflict is `ciOutcome` `conflict`, back to `do the work` with the same
  `rebase-<n>-1` finding and the same count as the rebase step (the third
  unresolved conflict in a row is an incident, on `mergeable?`; current or
  clean here resets the count, see "Bringing the PR up to date"), and the fixed round is pushed
  and probed again; current or clean waits (a clean merge is dropped,
  GitHub builds its own). What this leaves: main reverting, between the push
  and the probe, a change the branch conflicted with (the engine then sees a
  clean merge where GitHub saw none), and GitHub dropping the event; both end
  in the timeout, whose message says what to do. No Smith waits:
  the flow parks at a message (`ci check completed`), and `CiCheckWaits` reads
  the check run (`GET …/commits/<sha>/check-runs?check_name=…`) wherever the
  scan runs ("When it scans", below), waking only a flow whose check has
  completed. `success`, `neutral` and `skipped` go on to `security review`,
  as does a repo or branch the check does not apply to, straight from
  `open/update pr`.
  `failure` and `timed_out` go back to `do the work` with the finding
  `ci-<round>-1` (review `ci`): the failing tests and Kotlin compile errors
  from the job log, then the log's last 80 lines (at most 6000 characters),
  the check's link, and the check run's id for `read_ci_log` ("The frakworks
  server in a Smith"), which pages the whole log. No review runs on a failing branch: the fix is
  pushed and checked again like any round, then reviewed.

  **Jev triages the failure first** ("Jev accounts"), use case
  `ci-triage`: a `choice` over the finding's text (the state is the check's
  name, its conclusion and the finding as above) of `code` (the branch broke
  it), `infrastructure` (the runner, the builder, the network, a timeout, an
  OOM) or `flaky` (a test failing without the branch touching it). On
  `infrastructure` or `flaky` at confidence 0.8 or more
  (`engine.jev.ci-triage-floor`), and only once per round (`ciRerunRound`),
  `read the check` re-runs the check's Actions job (`POST
  …/actions/jobs/<id>/rerun`, the repo's token's `repo` scope) instead of
  sending the finding, sets `ciOutcome` to `rerun`, and the `re-run?`
  gateway goes back to the wait. The wait and `check timed out` pass over
  the run that was re-run (`ciRerunOf`, cleared at the next push), so only
  the re-run wakes the flow. A re-run that passes goes on to the reviews; one
  that fails is triaged again, and since the round has re-run already, goes
  to the work. Every finding Jev gave a pick for ends with that pick and its
  confidence, as a guess the work Smith weighs, and asks it to say in its
  answer whether the cause was the code, the infrastructure or a flaky test.
  **The fallback:** with no Jev answer (no jev account, a timeout, 429/529
  past the retries, any error), a pick of `code`, a pick below the floor, a
  second failure in the round, or GitHub refusing the re-run, the finding
  goes to the work exactly as before Jev. The call's `engine.jev_call` row
  has the pick and confidence (in `response`), the floor (`threshold`), and
  the `action` (`<pick>: re-ran the check`, `code: to the work`, `<pick>
  below floor <f>: to the work`, `<pick>, re-ran this round already: to the
  work`, `<pick>: re-run refused: to the work`, `no answer: to the work`).
  Its `outcome` is the re-run's result (`the re-run passed (<conclusion>):
  not the code`, `the re-run failed too (<conclusion>)`) or, for a finding
  Jev picked for, the work Smith's answer to it (`the work Smith's answer to
  ci-<round>-1: addressed: <how>` or `declined: <why>`), recorded by
  `readResult` when the next work run answers it. A floor above 1 turns the
  re-run off while keeping the calls and their outcomes.

  **A ci finding the work declines.** When Jev's triage sends a failure to the
  work and the work Smith finds it is not from the branch (a timing-sensitive
  test on a loaded runner, on 2026-10-06 #1157 and #1158 both failed this way
  on a desktop test neither touched), it answers `done` with no new commits
  and declines every open finding, each with its why, all of them `ci` ones
  and none addressed (`WorkResult.ciNotMine`, `bots/smith.md`). `readResult`
  takes that instead of refusing `done` without commits: it puts the last
  round's bundle back (`previousBundle`, saved by the collect before it set
  this run's), so the reviews after the check still read the branch's
  commits, and sets `ciNotMine`. On a ticket's first commit there may be no
  kept bundle (an incident's rerun drops it): `open/update pr` then bundles
  the pushed head again (`GitPush.headBundle`) for the reviews, so the decline
  is never refused for want of one (#1537); only a decline with no PR is.
  `open/update pr` pushes nothing (the head, its check and the bundle stay).
  When the check has since re-run and passed on that head, it re-runs
  nothing and the next wait takes that run; otherwise it re-runs the failed
  check run (`ciRunId`), notes it in `ciRerunPending`. A re-run already
  under way (a newer run on the head not yet completed, or GitHub refusing
  the re-run with 403 "already running", `RerunAlreadyRunning`) is taken as
  that re-run: nothing is posted, `ciRerunPending` is set all the same and
  the wait takes it, never an incident. Jev's triage re-run in
  `read ci check` takes "already running" as a re-run too. Either way it
  comments the round's reasons on the PR; `check
  needed?` makes it the next wait's `ciRerunOf`, so only the re-run wakes it.
  `ciNotMineRounds` counts such rounds in a row (a round with commits resets
  it); a third is refused as a broken rule, the incident, so a test that keeps
  failing stops for a person rather than looping. Before this, the Smith had no
  way to say so: `done` without commits broke the rules and parked the flow.
  That incident is cleared, once the test is judged flaky, with `act_on_incident`'s `retry` and `rerun_ci: true`
  (#2249, `IncidentRetrier.retry`): it sets `ciNotMineRounds` back to 0 and retries the read, so the stored decline
  is taken and `open/update pr` re-runs the failed check on the same head, with no new Smith. Each such retry grants
  at most `CI_NOT_MINE_MAX` (2) more automatic re-runs, so a test that truly fails comes back to a person. On any
  other incident `rerun_ci` is refused (400) and nothing is touched.

  Any other
  conclusion (`cancelled`, `action_required`, `stale`) is an incident on `read the check`: it says
  nothing about the code; re-run the check on GitHub, then retry. A check
  that has not completed after `engine.ci-check.timeout` (default `PT3H`) is
  an incident on `check timed out`, which reads the check once more first in
  case a wake was lost; retrying it looks again, so a retry after the check
  has completed goes on from its result, and one before fails again. When
  GitHub never made a run at all (`not started`), the message adds why that
  happens, a PR GitHub cannot merge (bring the branch up to date and push)
  or a dropped `pull_request` event, and how many re-triggers the engine
  tried.
- **A round re-run on a branch that already holds its commits.** A `done`
  with no bundle (a retry, or a later round that adds nothing, on a
  `smith/issue-<n>` already pushed ahead of the default branch) is not
  refused: `readResult` takes the pushed head (`GitPush.aheadBundle`) as the
  round's bundle, so `open/update pr` pushes it (a no-op) and the check and
  reviews read it as any round (#1722). With nothing on the branch ahead of
  the default, `done` without commits is refused as before.

  The engine re-triggers a dropped event itself: `re-trigger if not started`
  (`task-ci-check-retrigger`, on the wait's non-interrupting
  `event-ci-check-retrigger` timer) runs `engine.ci-check.retrigger-after`
  (default `PT20M`) after the wait begins and again after each such period
  while the wait lasts; the delegate re-triggers at most
  `engine.ci-check.retriggers` (default 2) times per wait and head, and a later
  firing past that bound reads nothing. When
  GitHub has made no run of the check on the head at all, it closes and
  reopens the PR, whose `reopened` event starts the check again, logs it and
  counts it in `ciRetriggers`; a run that exists, queued or slow, is left
  alone. The wait goes on as before, so a run the re-trigger produced ends it
  normally, and the timeout still bounds it. A failed close skips the try; a
  failed reopen fails the job (retried, then an incident), since the PR
  would otherwise stay closed. Waits migrated from an older diagram version
  have no re-trigger timer.

  The wait follows the PR's head, not only the engine's push: the waker, the
  re-trigger and `check timed out` each read the PR's current head first
  (`GET /pulls/{n}`), and when the branch has moved (a merge of main, a
  person's push) they write it to `headSha`, read the check on it, and start
  `ciRetriggers` over for it. GitHub never runs the check on a superseded head
  again, so a wait pinned to it could only time out (#1586). With no PR, or
  GitHub not answering, the stored head stands.
- `security reviews on?` (`gateway-security-review-on`, after the engine
  check in all three flows) routes on `securityReview` and `docsOnly`
  ("Docs path"): false, or a docs-only push, goes straight
  to `review pr`, and no `security` Smith runs for the round; true, or unset
  (an instance pushed before the setting), goes to `security review` as its
  default flow. Every push passes it: the first, a rework round and a
  catch-up merge from `bring pr up to date`. The setting is the repo row's
  `security_review` (`POST`/`PATCH /repos`, the desktop's Config > Repos
  checkbox), on by default; a repo with no row has it on. With it off the
  flow view and `📊 Run stats` simply have no security step to show.
- `security review` is a `security` Smith on the pushed round: the round's
  bundle in `/work/in/commits.bundle`, now also on the PR branch, reviewed
  against the default branch. Findings go back to the work; they stay in the
  brief and never reach GitHub (the round comment names only the ids the
  work addressed). Only a pass goes on to `review pr`.
- `review pr` is a `review` Smith (correctness) on the pushed PR. Approve
  goes to the merge; anything else, as the gateway's default flow, back to
  the work, and from there through the push, the engine check and both
  reviews again.
- `bring pr up to date` (`${rebasePr}`, below) runs between an approval and
  the merge.
- `merge pr` (`${mergePr}`) squash-merges; `close ticket` (`${closeTicket}`)
  comments and closes the ticket. GitHub's 405 "Pull Request has merge
  conflicts" is the BPMN error `merge-conflict`, caught on the task and sent
  back to `bring pr up to date`.
- `close ticket, nothing to do` (`${closeNothingToDo}`) is where a work
  Smith's `nothing-to-do` outcome goes: the ticket needs no change (what it
  asks for is already true), so the Smith committed nothing and says why in
  `reason`. The step comments `Closed: nothing to do. <reason>` and closes
  the ticket as completed; there is no review, PR or merge, and the flow
  ends at `nothing to do`. `readResult` refuses a `nothing-to-do` with no
  reason, with commits (`done` gets them reviewed), or with findings open.
  Bug and feature route it the same way from their work gateway. Like the
  merge close, it counts as satisfied for "Blocked by" and wakes a split
  parent ("Split features").
- A work Smith's `ask` outcome goes to `post the smith's question`
  (`${postFeatureQuestion}`, the feature's delegate), which records the
  question in `engine.intake_question` under the chore's instance id and
  posts it on the ticket; the chore then waits at `answer the smith's
  question`, and the answer runs the work again with `answers` in its brief.
  Answering is the feature's ("Answering", under "Feature"), for a trusted login.
- A dry run writes canned results that reach no GitHub: a work run asks
  (so the chore waits at the question, answerable like any other), reviews
  pass.

## Bringing the PR up to date

Before this step, a chore whose PR fell behind main while it was reviewed
parked at `merge pr` on GitHub's 405 (#122, #145, #176, #186, #87 on
2026-09-25). `bring pr up to date` (`RebasePrDelegate`) merges the default
branch into `smith/issue-<n>` with `GitPush.mergeDefault`, in the same
blobless bare clone `push` uses, and pushes nothing itself (`smith/` branches
only):

- **current** (`merge-base --is-ancestor`): the branch has the default
  branch already; on to `merge pr`.
- **behind but clean**, on the way from an approval: also on to `merge pr`,
  as it is. GitHub squash-merges a PR that is not up to date, and the engine
  check already ran on GitHub's merge of the branch with the default branch as
  of the push; the default branch's own build (images.yml) runs the tests on
  the result, and a failed build deploys nothing. Until 2026-10-06 a clean
  merge here was a catch-up round (below) every time: with many PRs merging,
  the default branch moved again before the round's check and reviews ended,
  so PRs chased it round after round and few merged.
- **clean** after GitHub refused the merge with its 405 (`merge pr`, below):
  `git merge-tree --write-tree` merges without a work tree, and
  `git commit-tree` makes the two-parent commit as the repo's token's account
  ("Each repo's token"). The
  merge comes back as a bundle (`oldHead..merge`) and goes through the loop
  like any round: `open/update pr` pushes it fast-forward and comments
  `Round <n>, catch-up: merged <default> into the branch; no changes from
  review`, then the engine check, then `security review` on the pushed
  merge, with a `merge` entry in its brief so it reviews the merge against
  its first parent, then `review pr`, then this step again. The merge is
  reviewed like any round, after its push: #199 decided on 2026-09-23 that a
  merge, too, was pushed only after security had seen it, and that order was
  reversed on 2026-09-26 with the rest of the loop ("Why the engine check
  runs before the reviews"). Security findings on a merge go back to the
  work like any others, and the work continues from the pushed merge.
- **conflict**: back to `do the work` with the finding `rebase-<n>-1`
  (review `rebase`): "merge conflict with the default branch in <files>",
  telling the Smith to fetch, run `git merge origin/<default>` first,
  resolve keeping both sides' intent, commit the merge and re-run the tests.
  The engine never edits file contents. The work round is then pushed,
  checked and reviewed as usual. A flow gets 2 consecutive unresolved
  conflict rounds (`conflictRounds`); the third conflict in a row fails the
  job, an incident on `bring pr up to date`, and nothing is merged. A round
  the work resolved (the next probe, here or on `mergeable?`, finds the
  branch current or merging cleanly) resets the count, so a hub file main
  keeps moving under does not strand the flow. A lifetime cap of 6 conflict
  rounds (`ConflictRounds.MAX_TOTAL_CONFLICT_ROUNDS`, counted in
  `conflictRoundsTotal`, never reset) still ends a branch contended forever
  on an incident naming the cap; `<n>` in `rebase-<n>-1` is that lifetime
  count, so ids stay unique across a reset. A human's retry of either
  incident grants another two rounds ("Incidents", a conflict-cap incident).

A 405 at `merge pr` comes back here; if git then finds the branch current,
the job fails rather than loop. The step is shared by all three flows: bug
and feature run it the same way between their approved review and `merge
pr` ("Bug", "Feature"), and what follows the merge stays each flow's own.
The same merge, and the same conflict finding and count, run once more
before the engine check waits (`mergeable?`, under `wait for the engine
check` above), because GitHub makes no check run for a PR it cannot merge;
that probe pushes nothing, so a branch merely behind main is not caught up
there, only here.

## Why the engine check runs before the reviews

Decided on 2026-09-26 (#413), reversing the 2026-09-23 decision on #199 that
security reviewed each round's bundle before anything was pushed. That order
spent reviews on code that did not build: on #410 security passed a round
twice, and each time the pushed branch then failed the engine check and went
back to the work. Both security runs had read a branch that was not what
would merge. The check needs a pushed head (it is a GitHub check run on the
PR branch), so putting it before the reviews means pushing first.

So every round, catch-up merges included, now runs: do the work, open/update
pr (the push), the engine check, security review, review pr, bring pr up to
date, merge pr. Both reviews see a branch that builds and passes its tests,
and a failing branch costs a work run and a check, not a review. A repo the
check does not cover goes from the push straight to security.

What it gives up:

- **Security findings are no longer private.** The round is public in the
  PR's commit history before security sees it, and stays there after a fix
  commit, a squash merge and the branch's deletion (GitHub keeps a PR's
  commits). The engine still keeps the findings themselves off GitHub (the
  round comment names only the addressed ids), but that hides the pointer,
  not the hole: a secret that reaches a round is leaked and must be
  rotated; removing it in a later commit does not unpublish it. On a
  private repo only collaborators see the PR.
- **Unreviewed code now runs on the build runners.** Before, only code that
  security had passed was pushed, so only that reached CI. Now each round's
  code builds and runs its tests on the self-hosted `build` runners, in the
  persistent `frakworks` builder, before any review. That builder's
  `/gradle-cache` mount and layer cache are shared with `images.yml`, which
  builds and publishes the engine and Smith images ("Images"). A hostile
  round (a prompt-injected work Smith, say) could poison that cache or the
  runner before security reads it, and the next image build would pick it
  up. This stays open until the PR check is isolated from the image builds
  (its own builder or cache for `pull_request` runs, or an ephemeral
  runner).

What neither order covers: holes already in the repo (public either way),
and what a work Smith could leak itself while it holds its repo's token (its
`git push` and `gh pr` commands are denied; a read-only token is the rest).

## Hitlp changes wait for the user

A PR that changes the hitlp module (`engine/modules/hitlp/**`) or one of its
Flyway migrations (a `db/migration` file named `V<n>__hitlp*.sql`) is not
merged until the user accepts it, whatever the automated review said. It is a path rule in the
engine's own chore, bug and feature flows, not CODEOWNERS:

- **The push decides.** `open/update pr` sets `hitlpChange` at every push
  (`HitlpPaths`, from the same changed paths `docsOnly` reads) and removes
  any earlier `mergeAccepted`, so each pushed head is accepted on its own.
- **After an approving review and an up-to-date rebase**, `hitlp change?`
  sends such a PR to `ask the user to accept the merge`, which posts the PR
  on the ticket, records the question and files it on the HITLP server as a
  `human.approve` (`action` "merge PR #n of owner/repo", `payload {repo, pr,
  head}` with the pushed head, `requiresHuman`, 24 h; "The HITLP requester"),
  the comment ending with the hint where to decide it. The flow waits at
  `user-task-answer-merge`: a `GET /needs` row of kind `acceptance`. An
  `approved` record sets `mergeAccepted` and the PR merges; `rejected`,
  `timed_out` or `cancelled` is a `merge-<round>-1` finding with the record's
  `reason`, back to `do the work`.
  A merge conflict after the acceptance re-runs the rebase; a clean catch-up
  merge is a new push and asks again.
- **Every other PR** goes from `up to date?` straight to `merge pr`, as before.

## Docs path

Documentation is low-friction in any target repo, and a docs-first repo
defaults to it (#2339). Two parts, both decided by the engine:

- **The push decides, in every repo.** `open/update pr` sets `docsOnly`
  at every push: true when every path the branch changes against the default
  branch is documentation (`DocsOnly`: `*.md`, `*.mdx`, `*.markdown` anywhere,
  anything under a `docs/` directory, and `README*`), false otherwise and for
  an empty change. A docs-only round in a chore, bug or feature goes from the
  engine check to `review pr` with no `security` Smith, the way a repo with
  `security_review` off does; the correctness review and the merge are
  unchanged. A round whose push touches any other path takes the full
  pipeline, so a mixed diff, or a later round that adds code, gets its
  security review again: merge safety for code is never relaxed.
- **A docs-first repo defaults to it.** The repo row's `docs_first` (V73,
  false by default; `PATCH /repos/{owner}/{name}` `{"docsFirst": true}`,
  First mate's `edit_repo`, the desktop's Config > Repos switch) puts
  `docsFirst: true` in a refine run's brief (false in any other repo), and the
  refiner then files the ask as a `chore` at a low tier with no design run
  (`bots/model-choice.md`, "The rules"). A prescribed `Tier:` still binds. The
  setting never skips a review by itself: the push's `docsOnly` still decides,
  so a docs-first repo's code change is reviewed in full.

## After the merge: the post-deploy check

When the PR has a `## POST-DEPLOYMENT-CHECK` section, the chore flow does not end at its merge: it calls
`post-deploy-check` (`call-post-deploy-check`), which waits for the merge to reach production, runs check
Smiths until one passes or fails, asks the user at the deadline, and closes the ticket. See
[post-deploy-check.md](post-deploy-check.md).
