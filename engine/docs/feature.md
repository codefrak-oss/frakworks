# Feature

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

`bpmn/feature.bpmn` (process `feature`) is the flow for a ticket of kind
`feature`, started like a chore by a `feature` label with `authorized`
(by a trusted login), or from an intake ("Authorization from
an intake"). A bug wraps the chore's loop in evidence; a feature wraps it in
agreement: a plan before the work, and acceptance after the merge. Decided
with the user, 2026-09-24.

    design → planned? ─→ do the work → pr → engine check → security review → review → up to date → merge → check acceptance → accepted? ─→ close ticket
       │  questions for the user (confirm)      (the chore's loop, delegates shared)                                                  │ unmet
       └──→ post question → answer → (design again)                                                                                   └──→ report, leave open

**Two modes, and every feature runs in one of them.** The mode is part of
the approval: an issue authorized by hand carries `feature:auto` or
`feature:confirm` next to `feature` (the scan refuses a feature with
neither or both, "The scan"); one filed by an intake carries the mode the
refiner chose (`featureMode` in its result, required for a feature): the
refiner files a feature as `auto` unless the user names `confirm` in the
ask, and a mode the user states binds. It reaches the flow as `featureMode`.

- **auto**: the design Smith's assumptions all stand. The user is asked
  nothing unless a Smith hits an `ask` outcome.
- **confirm**: the design Smith's important questions go to the user before
  the work starts, with the plan's summary (mockups included) ahead of them.

The modes differ only before the work: in both, a Smith checks acceptance
after the merge. Until #583 (2026-09-27) a confirm feature waited after the
merge for the user to accept or reject a checklist; that gate is gone
(below, "Instances started before #583").

The steps:

- `design` is a Smith run in `design` mode on the default branch. It commits
  nothing and writes `DesignResult`: `planned`, with a `plan` (summary, files,
  stages in order, tests, and the ticket's acceptance criteria as checkable
  sentences) and `questions`, or `ask`. **Every question carries an
  `assumption`** (what it does without an answer), a `confidence` 0–1 that the
  assumption is what the user wants, and an `impact` (low, medium, high).
  A question is **important** when `confidence < 0.7` or `impact` is high
  (`DesignResult.CONFIDENCE`). A question whose answer is one of a few
  candidates may carry **`options`**: 2 to 5 `{"id", "label"}`, ids unique
  and non-empty, labels non-empty (a label over 40 characters is shortened on
  its button, not refused); `PlanQuestion.problems` holds it to the rules of a
  `choice` ask block (`bots/smith.md`, "The four kinds"). `read result` keeps the plan as `plan`, in
  every later brief, and in confirm mode turns a plan with important
  questions into the `ask` outcome: `post the smith's question`
  (`${postFeatureQuestion}`) posts them as one question (the plan's summary
  under "The plan:", its inline SVG mockups included, ahead of the
  questions; each question with its confidence, impact and assumption; then
  an ask block; the desktop renders the inline SVG, GitHub's page strips it) and the flow
  waits at `answer the smith's question`. The block is `kind: choice`, its
  options the desktop's buttons, when exactly one question is asked and it
  carries options; with several questions, or one without options, it is
  `kind: text`, since a comment carries one block (`PlanQuestion.userQuestion`,
  shared with a propose run's questions). An answered choice arrives in
  `answers` as the option's id, or the user's own text: a choice ask's
  `responseSchema` is a plain string (no `enum`), so the desktop offers
  **Something else** with a text box; proposal and research verb menus keep
  their `enum`. A design Smith's own
  `ask` ends in a `choice` block when the answer is a menu, `text` otherwise. In auto mode every assumption
  stands, however unsure.
- **Answering.** On the HITLP server, as an intake's question is: the decider
  must be a trusted login; `HitlpAnswers` reads the decision record. The engine records the answer in
  `engine.intake_question` under the feature's id, comments it, and the
  flow goes on with the whole exchange in the brief as `answers`: the design
  runs again from the ticket plus the answers, and a work Smith's `ask`
  resumes the work the same way (as in the chore and the bug). `GET /intake/{id}`
  and `GET /questions` show a feature's pending question like an intake's, so
  the desktop app's ask card answers it unchanged.
- `do the work` is the chore's work run, told the plan (`plan` in the brief):
  it follows the stages; an answered question's answer binds, an unanswered
  one's assumption stands. PR, the engine check, security review, review,
  bringing the PR up to date and merge are the chore's, in the chore's
  order, with the same delegates and the same merge-conflict handling
  ("Bringing the PR up to date", under "Chore"): a branch behind main gets
  the merge pushed, checked, security-reviewed and reviewed again, a conflict goes back to `do the work` with a
  `rebase-<n>-1` finding, at most 2 consecutive unresolved conflict rounds (a resolved round resets the count; lifetime cap 6), and GitHub's 405 at
  `merge pr` comes back to the step. The correctness review also checks the
  work against the plan.
- `check acceptance` after the merge, in both modes: a Smith run in `accept`
  mode on the default branch, writing `AcceptResult`: one `criteria` entry
  per plan criterion, `met` true, false or null (nothing here could check
  it), and an outcome `accepted`, `unmet` or `unverified`. `accepted` and
  `unverified` go to `close ticket`, which quotes the checklist and says
  when something was not checked; `unmet` goes to `report not accepted`
  (`${reportUnmet}`), which comments the checklist and leaves the ticket
  open. Either way the flow ends: another round would start from a PR that
  has already merged.
  An accept result the rules refuse runs the check once more with a new
  Smith told why ("Incidents", "A rejected work result").
- **Instances started before #583.** A running instance stays on the
  process version it started on, so a confirm feature merged before #583
  may still wait at `accept or reject` (`user-task-answer-acceptance`). For
  those only, the engine keeps the old steps' delegates and their Jev use:
  `post the acceptance checklist` (`${postAcceptance}`) had commented the
  plan's criteria and recorded them as the pending question; the user
  answers on the HITLP server, and `read the answer`
  (`${readAcceptance}`) reads an answer that starts with accepted, yes, ok,
  done, lgtm or approved as `accepted` (close), anything else as `rejected`
  (report, leave open). Before a first answer is read as rejected, it is
  put to Jev ("Jev accounts", use case `acceptance`): a `noul` over the
  checklist and the answer, "does the user accept the work as it stands?".
  At `noul` ≥ 0.9 (`engine.jev.acceptance-threshold`) the outcome is
  `reask`: `ask: did you mean accepted?` (`${postAcceptanceReask}`) asks
  once more, and the reply is read by the rule alone and becomes the Jev
  call's `outcome` (`confirmed accepted: …` or `not accepted: …`). Below
  0.9, or with no answer from Jev, the rejection stands; the call's
  `action` says which (`re-asked`, `kept rejection`, `no answer, rule`).
  The needs board still lists such an instance as an `acceptance` need.
  Once none is left, these delegates, the threshold, the Jev use case and
  the need kind can go.
- A dry run writes canned results that reach no GitHub: the design run asks
  (so the feature stops at the question), an accept run says unmet.

**`Builds:`: a feature that builds an accepted proposal.** A proposal's
`accept and build` files a feature whose body starts
`Builds: docs/proposals/<slug>.md` ("Proposal"); any ticket body may carry
the line, alone on its own line (`Builds on #200` in prose is not one).
`Brief.builds` parses it from the ticket snapshot, and every brief on that
ticket carries the path as `builds`. The design prompt says the doc is the
agreed design: plan from its "Implementation outline", treat its "Proposal"
section as binding, and ask only where the doc leaves gaps. The work prompt
says to mark it built in the same PR, so the edit is reviewed with the rest
of the diff: append `- <yyyy-mm-dd> built in #<n>` to the doc's
`Decision log:` and set `Status: built`, nothing else in the doc. `<n>` is
the brief's `pr.number` when there is one; in round 0, before the engine
opens the PR, it is the ticket's own number, which GitHub links to its
closing PR. The engine does not rewrite the line after the PR opens
(decided with the user on #410). The correctness review checks the log line
and Status, and makes a departure from the doc's "Proposal" section a
finding. A split of such a feature puts the parent's `Builds:` line at the
top of each child's body (`fileChild`), so each child's runs are told the
doc too; a child whose body says `Part N of M` logs
`built in #<n> (part N of M)`, and only the last part sets `Status: built`.

Not built: staged work as separate runs (the plan's stages order one work
run), and the `incident` kind, which has no flow (a `proposal` and a
`research` have their own: "Proposal", "Research").

### The design deck

A confirm-mode design whose plan goes to the user is also shown as a dark,
slide-like HTML presentation (#100114). The engine templates it; the Smith
writes nothing new. When `post the smith's question` posts a design run's
plan (not a design `ask`), it keeps that `DesignResult` as the flow variable
`designDeck` and adds one line to the comment pointing at the desktop. The
comment's text and its ask block are otherwise unchanged: they stay the
readable fallback on GitHub and the answer path, and the answer resumes the
flow as before. Auto mode never posts the plan, so it never sets `designDeck`.

`GET /ticket/{owner}/{repo}/{n}/design-deck` answers `{html}`:
`DesignDeck.render` of the newest feature flow's `designDeck` on the ticket,
running or ended; 404 for a ticket without one or a repo that is not a target.
The page has a title slide (the summary, its inline `<svg>` mockups as
figures), then Files, Stages, Tests and Acceptance, then one slide per
important question with its impact badge, a confidence meter, the assumption
and the options. Every text is HTML-escaped; only the `<svg>` mockups are
carried over, stripped of scripts, event handlers, `foreignObject` and
non-fragment links. The page has no script and loads nothing, and passes
`PitchRules`. The desktop draws it in the pitch frame (`src/pitch-frame.js`:
sandboxed srcdoc, its own CSP, no network): as the ticket detail's
**Proposal** section, and through **Open presentation** on the decision card,
which shows only when the ticket has a deck.

## Split features

Decided with the user 2026-09-25 ("tickets that make tickets"), after three
work Smiths overflowed their context window on 2026-09-25 (#113, #102, #120,
each `Prompt is too long` after 60-90 turns) on tickets a human then had to
split by hand. Replaces the design intent of #152.

    design → split ─→ file the next child → child closed (the scan wakes it) → replan → apply → drafts left? ─yes→ (file the next child)
                                                                                                            └no→ check acceptance (the parent's own)

- **The `split` outcome.** The design Smith prefers one work run: it returns
  `planned` whenever the plan fits one PR of reviewable size, since every
  child costs its own design, reviews and acceptance (#272). Only when one
  work run would overflow its context window, or the parts are independently
  shippable, does it return `split` (a rerun after an overflow is pushed
  toward it, below) beside
  `planned` and `ask`: the same `plan`, plus `children`, an ordered list of
  2-16 drafts (prefer 2-3), each `{title, body, kind: chore|bug|feature,
  dependsOn: [indexes of earlier children],
  points}` in the filed-ticket shape the refiner uses. A feature child takes
  the parent's mode, auto or confirm; a `featureMode` on a child is ignored. The schema requires
  `kind`, `dependsOn` (an empty list allowed) and `points` on every child, so
  `check-result design` refuses a child without them. `readResult` checks the
  list (`ChildDraft.problems`): a known kind, a mode for a feature, points one
  of 1, 2, 3, 5, 8, 13 (`bots/story-points.md`), dependencies on earlier
  children only, no push-to-main grant. A revise's children obey the same
  rules. Filing a child writes its points into the trailer (`· points <n>`)
  and its `engine.handled_issue.points` row, as for an intake-filed ticket, so
  `GET /ticket` and the board's split-parent progress (`children[].points`)
  return them. The plan is kept as
  `plan` and the drafts as `split` (`SplitState`: `drafts` left, `filed`
  children with their state and PR, `dropped` titles).
- **The repo's child-work mode** (#2501, part 1 of #2499): `engine.target_repo.child_mode`
  (V86), `serial` by default, `parallel` or `live`, set through `PATCH
  /repos/{owner}/{name}` `{"childMode": ...}` (never from a Smith sandbox: 403),
  First mate's `edit_repo`, and the desktop's Config > Repos > Edit… > Docs path
  **Child work** choice. `serial` is this section's behaviour: one child at a
  time, a replan after each. `parallel` files every ready child at once and
  replans after each close ("Parallel children"); `live` files the same way
  and runs one long parent Smith while they are worked ("Live parent"). Under `parallel` and `live` the design run's brief
  carries the mode as `childMode` and the design prompt adds a paragraph: prefer
  children that can run at the same time, keep `dependsOn` to true ordering
  constraints only, and say in the plan's summary which children are
  independent; under `serial` the prompt is unchanged. When a design result
  splits, `FeatureResultHandler` freezes the repo's mode on the feature
  instance as `childMode` (`Vars.CHILD_MODE`), and every later run on the
  feature's instance (replan, accept) gets that frozen value in its brief,
  never the repo row's, so changing the repo mid-split does not change a split
  under way. Under `parallel` and `live` the children are filed and woken as
  "Parallel children" says, below; under `serial` they are filed one at a time.
- **Children are filed one at a time**, never all up front. `file the next
  child` (`${fileChild}`) takes the first draft whose dependencies merged
  or were satisfied (below),
  appends `Part N of M of #<parent>.` to its body, files it through the
  intake's `TicketFiler` (kind label, a feature's mode label, the trailer
  `<!-- engine: split of #parent · tier n · p2 -->` at the parent's tier),
  as a work item, records the parent as **blocked by** the child in the
  work-item store (`Tickets.block`, a store dependency: the write side
  of the relation "Blocked by" reads), and starts the child's flow as
  an intake carry-over does ("Authorization from an intake"): the parent's
  `authorizedBy` must be a trusted login, the claim is label
  event 0 with detail `authorized by @x via parent #n`, and a child whose
  authorizer is not trusted waits for its own `authorized` with a comment.
  **A child is never looser than its parent**: under a `confirm` parent every
  feature child is filed and started as `confirm`, whatever the draft says.
  The parent then parks at `child closed`, an intermediate message catch,
  backed by an `engine.deferred_issue` row whose `waiting_instance` names
  the parent's instance.
- **Waking.** The scan's resume pass treats such a row like a deferred
  approval: once every blocker closed and each close was the engine's own
  (its merge, or its nothing-to-do close), it correlates `child closed` to
  the parent (`closedChild`, and `closedChildPr` from GitHub's closer or the
  child flow's `pr`) instead of starting a flow, reported as `resumed`.
- **A child with nothing to do.** A child's work Smith that finds the part
  already done returns `nothing-to-do` with a reason ("Chore", `close
  ticket, nothing to do`): the child closes as completed with no PR. The
  wake carries no `closedChildPr`, and the parent records the child as
  `satisfied` (no PR), which a later draft's `dependsOn` accepts like
  `merged`. The replan is told the child closed with nothing to do, and
  `apply the replan` comments `Child #n closed with nothing to do; …`; the
  flow goes on as after a merge. A feature child that merged and closed
  its issue but whose flow is still at its acceptance check (or, for a
  confirm child started before #583, at the user's acceptance) is not a
  close without a merge, whether the engine closed it or GitHub did at the
  merge of a PR carrying `Closes #n` (#350): the parent waits on silently (no comment, no
  `reauthorize` need, and a re-authorize does not wake it) and is woken
  with the child's PR once the child's flow ends: when it is accepted,
  at once, without waiting for a scan (the close wake, "When it
  scans"). A child whose criteria are unmet ends without its close, so the parent waits on. A child **closed without a merge**
  gets the same `Blocker #n was closed without a merge; re-authorize to
  proceed` comment once, adding that re-labelling the parent `authorized`
  resumes it and treats the child as satisfied, and the parent stays
  parked: no replan.
- **Resuming a parked parent.** A fresh `authorized` on the parent is
  normally refused as still running. When its `engine.deferred_issue` row
  has a `waiting_instance`, no blocker is open and one closed without the
  engine's merge, the scan (`IssueScanner.consider`) instead gives the
  approval the checks a start gets (a trusted login, one
  kind and one mode, no kind change and no edit after it), then correlates
  the same `child closed` message with no PR: the child is recorded
  `satisfied` and the flow goes on to the replan. The approval is claimed
  in `engine.handled_issue` as `resumed`, detail naming the child, with the
  parent's instance; the deferred row is removed and the scan reports
  `resumed`. A parent whose child is still open, or any other running
  flow, is still refused. If the instance no longer waits at `child
  closed`, the claim is given back and the scan reports a failure.
- **Only the current child decides.** GitHub keeps the parent blocked by
  every child filed before, so for a `waiting_instance` row both the scan
  and the resume pass read only the child (under `parallel`, the children)
  `FileChildDelegate` recorded in the row's `blockers` (which the scan keeps narrowed to it). A child
  closed by hand and resumed past never stalls the parent again, and the
  next child's merge wakes it with that child and its PR.
- **Parallel children** (#2519, part 3 of #2499). Under `childMode`
  `parallel` (and `live`, for the filing), `file the next child` files and
  starts **every** draft whose `dependsOn` are merged or satisfied
  (`SplitState.ready()`) in one pass, each as above (its own `blockBy`,
  claim, start, part number and comment) and each recorded in `split`, with
  the child flow's instance, before the next; a failure after the first
  keeps what was filed, says so on the parent and parks, and the drafts not
  filed are still ready at the next pass. The parent then parks on one
  `engine.deferred_issue` row (replacing the last) whose `blockers` are
  every open filed child (`SplitState.open()`); with nothing ready and
  children open the pass only re-parks. **The wake is per child**: for a
  `waiting_instance` row with more than one blocker, the resume pass
  (`IssueScanner.resumeEach`) wakes the parent for the lowest-numbered
  child the engine's own close ended (its PR from GitHub's closer or the
  child flow's `pr`), narrows the row's `blockers` to the rest and forgets
  the row only when none is left. Children still open, or merged with
  their flow at its acceptance check, do not hold the wake; a child closed
  without a merge is said once per child (`Child #n was closed without a
  merge; the parent goes on waiting on its other children`) and the parent
  waits on the others. A re-authorize, by label or First mate's
  `reauthorize`, resumes it per child too: the lowest such child counts as
  satisfied, whatever its siblings' state. The close wake (`wakeWaitingOn`)
  goes through the same pass. `apply the replan` sets `childrenOpen` beside
  `draftsLeft`, and the `more to do?` gateway loops back to `file the next
  child` on `${draftsLeft || childrenOpen}`, which re-parks on the children
  still open; the parent goes to `check acceptance` only once every child
  closed and no draft is left. Under `serial` `childrenOpen` is always
  false, so the serial flow is unchanged. **A close arriving while the
  parent is at `replan`** (or anywhere but `child closed`) finds nothing to
  correlate: the pass fails for that row, leaves it as it was, and the
  child, still `open` in `split`, stays among the blockers the replan's
  re-park writes, so the next scan or close wake delivers it. The replan's
  brief lists, for each filed child, `state`, `prNumber` (an open child's
  open PR too, read from its flow's `pr` variable) and `running` (its flow
  still runs), and under `parallel` and `live` its prompt says siblings may
  still be open and that `steer` ("Steer") changes an open sibling's course.
- **Replan.** `replan` is a Smith run on main as it now is, told the plan,
  the child that closed and its PR (none for a satisfied one), and the remaining drafts (`split` in the
  brief). It returns exactly one of `continue` (file the next draft as
  drafted), `revise` (`children` replaces the remaining drafts, validated
  like a design's; a draft added or dropped shows in `dropped`), or `done`
  (the rest is dropped, nothing more is filed). **Filed plus remaining
  drafts may never pass 16 over the split's life**: a `revise` over that is
  refused as an incident. **Every draft left to file is a real ticket**:
  the revised `children`, and the remaining drafts a `continue` files as
  they are, each need a Goal section with content and an Acceptance
  criteria section with at least one item, and may not restate a filed
  child: its title, or a body that is its record. `Filed as #n` or `merged
  in PR #x` naming a filed child is its record only when the draft has no
  work of its own (no Acceptance criteria item, or a Goal with no words
  once those phrases are taken out); a real ticket that names a merged
  sibling as its dependency ("builds on the loader merged in PR #761",
  #751) is kept. A replan that breaks this is the same incident, its retry
  re-runs the replan, and nothing is filed. `apply the replan` (`${applyReplan}`) records the
  close (`merged`, `satisfied`, or `split`: "A child that splits", below), sets `pr` to a merged child's PR so the board and the close have
  one, and comments the outcome; `drafts left?` files the next or goes to
  `check acceptance`. The replan after the last child is the coherence check
  and returns `continue`.
- **Steer** (#2517, part 2 of #2499). Beside any outcome a replan may return
  `steer`, a list of `{child, message}`: a change of course for a filed child
  still `open`, without killing or restarting its current run.
  `ReplanResult.problems` (and `check-result replan`, from the brief's
  `split`) refuses an entry whose child is not a filed child in state `open`
  (the child the replan follows counts as closed) or whose message is blank,
  naming the child. `apply the replan`, after recording the close, delivers
  each: a comment `Steer from parent #<n>: <message>` on the child's issue,
  and `{by: "#<n>", at, message}` appended to the `steer` variable
  (`Vars.STEER`) on the child's root instance (found through
  `HandledIssues.startedBefore`), by `FlowStarter.steer` through
  `RuntimeService`. The child's run in progress goes on; every later run of
  the child, whatever its mode, carries the entries, oldest first, as the
  brief's `steer`, as `amendments` ride. The work prompt says a steer binds
  like an answered question; the review, security and accept prompts make an
  unaddressed steer a finding. A child whose flow has ended is named in the
  parent's replan comment as not steerable, and gets nothing. Under `serial`
  no sibling is open while a replan runs, so a steer only matters under
  `parallel` and `live`.
- **Live parent** (#2530, part 7 of #2499; one session per split since
  #100042). Under `childMode` `live`, after
  `file the next child` filed the ready drafts, the `live parent?` gateway
  (`${childMode == 'live' && liveFallback != true}`) runs `call-parent`, a
  `reusable-smith-run` in mode `parent`, instead of parking at `child
  closed`; no `engine.deferred_issue` row is written for it (the last one is
  removed), so the scan has nothing to wake. The parent Smith (`bots/smith.md`,
  "Parent mode") waits on `await_children` (below), reads its children with `read_ticket_runs`,
  `read_transcript` and `read_ticket`, steers them with `steer_child`
  (the parent-scoped tools: the engine finds the run's split from its run id,
  `parentSplit`), and files the later drafts itself with `file_children`
  (below) as their dependencies close, so one session spans the whole split.
  When every draft is filed and the last child closed it writes a `ParentResult`
  `done` (with `steer`); `ended` when its session must end first. A replan's
  `continue` or `revise` is still accepted, for a parent that did not file
  through `file_children`. Its bounds are the Kubernetes Job's 24 h deadline
  (`engine.smith.k8s.active-deadline`, every Smith Job's) and the parent's
  turn budget (`engine.smith.parent-turns`, 1000; `smith-runs.md`, "Turn
  budget"); a failed, reaped or result-less parent run reads as `ended`
  (`ReadResultDelegate`, `parentEnded`). Then `reconcile the children`
  (`${reconcileChildren}`, `ChildFiling.reconcile`) records on `split` each open child that GitHub
  shows closed by the engine's own close (the parent's blocked-by list, the
  child flow ended with its merge or nothing-to-do close), with its PR, and
  sets `liveClosed` when no child is left open. With `liveClosed`, `apply the
  replan` applies the parent's result as a replan's (`done` with every draft
  filed changes nothing; an `ended` written after
  every child closed keeps the drafts, as `continue`), comments `Every child
  closed under the live parent; …`, and `more to do?` goes on as for a
  replan: with no draft left it goes to `check acceptance` with no second
  parent run; a draft left (an old-style `continue` or `revise`) is filed and
  a new parent run watches it. **The fallback**: with a child still open, `note the
  live parent ended` (`${noteLiveEnded}`) comments `The live parent session
  ended (<why>) with #a, #b still open; continuing one child at a time`, sets
  `liveFallback`, and returns to `file the next child`, which re-parks the
  parent on every open child at `child closed`; from there the split runs as
  under `parallel`, one replan per close, until a trusted login restarts the
  live parent. **The restart** (#2572): `child closed` sits in the
  `parked` sub-process, whose `restart live parent` boundary message
  `POST /ticket/{owner}/{repo}/{n}/live-parent` (a trusted login's bearer
  token, never a Smith) or First mate's `restart_live_parent` tool
  (frakworks-engine-diagnostics) correlates; both answer 409 and change
  nothing unless the split's `childMode` is `live`, `liveFallback` is set and
  the parent waits at `child closed`. `restart the live parent`
  (`${restartLiveParent}`) clears `liveFallback`, deletes the parent's
  `deferred_issue` row, comments `The live parent was restarted by @<login>;
  it watches #a, #b`, and starts `call-parent`. The children keep running
  untouched; one that closed in between is recorded by `reconcile the
  children` after the new parent run, as always. A
  child that splits while the live parent runs finds no parent parked and
  fails as an incident; retry it after the fallback or the next re-park.
- **file_children** (#100042). The live parent's own filing, a parent-only
  tool on the `frakworks` MCP server (`mcp/ParentTools.kt`, refused for any
  other run and for a work-item split, whose drafts are the flow's):
  `file_children({outcome, children?, drop?, summary?})`. `outcome` is a
  replan's: `continue` keeps the drafts left, `revise` replaces them with
  `children`, `done` drops them; `drop` (with `continue`) drops the named
  drafts first. `EngineCalls.fileChildren` hands it to `ChildFiling`
  (flow-feature, the `ChildFiler` contract in engine-smith-api), which checks it
  under `replanRules` against the split as it is (a refusal changes nothing),
  applies it (`SplitState.revised`, `done`, drops), records the children the
  engine's own close has closed (`reconcile`, the same pass `reconcile the
  children` runs, so a draft waiting on the close is ready now), and files
  every ready draft through the same `file` the delegate uses: the same
  `TicketFiler` body and `Part N of M of #<parent>.` line, blocked-by,
  `authorized by @x via parent #N`, the `Filed child #n` comment, the child's
  flow started. The split is written on the feature instance through
  `RuntimeService` (as `steer` writes a child's steer) and on the parent's own
  `smith-run` instance: `call-parent` copies every variable in at the start and
  out at the end, so a copy left stale on the run would undo the filing when
  the run ends. The tool answers `{filed: [{number, title, part}], draftsLeft,
  dropped, open}` and comments `The live parent replanned: <outcome>, …; filed
  #n` on the parent; the next `await_children` re-reads `parentSplit` and
  watches the new children. `LiveParentProcessTest` drives a scripted parent
  that files mid-run and ends `done`.
- **Children ask the parent** (#100049, part 3 of #100041). A child's
  question (a work run's `ask`, a design's open questions, a "Stop and ask
  if") goes through `post the smith's question` (`PostFeatureQuestionDelegate`,
  bound in every flow) to a HITLP request the user decides on the HITLP
  server (a question is no `/needs` row since #100013). Under a running live parent it goes to the parent first: the delegate asks
  `LiveParents` (engine-smith-api) whether the child's ticket is an open child of
  a live split whose feature instance is at `call-parent` with a parent run
  under it (`childMode` `live`, `liveFallback` not set; the parent's number
  from the child's `Part N of M of #P` line). If so, `ParentQuestions.ask`
  (flow-feature) records the question in `engine.intake_question` with
  `asked_of` `#<parent>` (V99), comments on the child that the question was
  asked of its live parent, and publishes a `question` `asked` event with the
  text (`docs/events.md`); no HITLP request, and the user's question list
  (`open()`) leaves a row with `asked_of` out, and the child's flow waits at its
  `user-task-answer-*` task as before. `ChildWatch` journals `question`
  `asked` as a waking frame with its `text`, so `await_children` reports it.
  The parent then calls **`answer_child`**, a parent-only tool beside
  `await_children` and `file_children`: `answer_child({child, answer})`
  completes the child's user task the way `HitlpAnswers.answer` does for a
  person (the row answered by `#<parent>`, an option's value of a choice block
  written as `option <id>: <label>`, `answers` on the child's flow so its next
  brief carries `{question, answer, by: "#<parent>"}`, the comment `Parent
  #<n> answered:` on the child, a `question` `answered` event);
  `answer_child({child, escalate: true, note?})` clears `asked_of` and files
  the HITLP request exactly as today's path does (`HitlpFiling.ask` with the
  same `nth`, ask block, asker and run id, `hitlpTaskId` on the flow, the
  `needs` `question` `added` frame), so the user sees exactly the row they
  would have had, with the parent's note on the child's comment; the child
  then waits for the user's answer through `HitlpAnswers`. `note the live
  parent ended` escalates every question still waiting on the parent before
  the fallback goes on. In every other case (`parallel`, `serial`, no split,
  a live split in fallback, a parent run that ended) the question takes
  today's path byte for byte, and a question the user was already asked is
  never re-routed (a refile after a timeout stays `HitlpAnswers.refile`).
  The child's work brief carries `liveParent: {number}` while a live parent
  runs, never otherwise, and `run-smith.sh`'s work prompt plus `bots/smith.md`
  tell the child to ask the parent where it would otherwise assume; what is
  worth a question is unchanged (no confidence threshold moves), only who
  answers it. `LiveParentProcessTest` drives a child's `ask` under a running
  parent (answered and escalated), under `parallel` and under the fallback.
- **await_children** (#2571). The live parent's wait, in place of the
  `sleep 300` Claude Code's harness would not let it run: a parent-only tool on
  the `frakworks` MCP server (`mcp/ParentTools.kt`), refused for any other run as
  the other parent tools are. It blocks on `mcp/ChildWatch.kt`, which takes every
  frame this replica's `EngineEvents` delivers (`onDeliver`: its own and the
  other replicas' relayed ones) into a bounded journal (5000 frames) and signals a
  condition: no polling. A frame is journaled when it is about one of its
  split's children (`number`, or a `push` to `smith/issue-<n>`) in its repo and is
  a `session`, `flow`, `ticket`, `needs`, `incident` or `push`, but only a
  significant one ends the wait (#2591): a `flow` whose root moved to another
  stage that is not a gateway (the run before it ended with its outcome: work
  done, security or review findings, a question, a PR opened, a merge) or that
  ended or was cancelled; a `ticket` `closed`; a `needs` `added` (a question);
  an `incident` `open` (a CI failure, a stuck step); a `push`; a `question`
  `asked` of the parent, with its `text` ("Children ask the parent"). A `session` frame
  and a `flow` step inside a run (`start smith`, `smith exited`) or at a gateway
  (`outcome?`) are bookkeeping: journaled, never a wake of their own. After the
  first waking frame the call waits 30 s more (never past the cap) and answers
  `{changed, changes: [{child, event, state, step, …}], cursor}` with every frame
  after the cursor, bookkeeping included, else
  `{changed: false}` at the cap, with the cursor where it was so the
  bookkeeping comes with the next wake: `GET`/`PUT /settings/parent-await-cap`
  (`{seconds}`, 60 to 3600; row `parent.await-cap-seconds` of
  `engine.engine_setting`, seeded 900 by V90). The cursor is
  `<process>:<seq>`; passing the previous one returns only what is new, and a
  cursor of another replica or an earlier process is read as none
  (`cursorReset`). Over HTTP the call is streamed (`mcp/McpStream.kt`): when the
  request accepts `text/event-stream`, the engine answers SSE at once (Claude
  Code allows 60 s to the first byte), sends `notifications/progress` for the
  request's `progressToken` every 30 s (an SSE comment when it sent none; its
  idle timeout is 5 min), and the JSON-RPC result last. A parent's
  `read_transcript` takes a `cursor` too (`s|time|count`, ending each answer)
  and returns only the events after it. Every answer also carries the parent
  run's `deadline` (its start, `smith_run_stats.started`, plus
  `engine.smith.k8s.active-deadline`, ISO-8601) and `remainingSeconds` to it
  (#2596), so the parent writes its result before the Job is cut.
- **Notes across waves** (#2596). A live parent session can end unplanned (a
  restart, a crash, eviction, the deadline) and leave no result; the next
  parent session, after a `restart live parent`, starts cold. `note_for_next_parent({text})`, a parent-only tool on the
  `frakworks` MCP server (`mcp/ParentTools.kt`, refused for any other run, at
  most 8000 characters), writes one row of `engine.parent_note` (V92: the
  split's feature instance, repo, issue, the run, `noted_at`, the note) at
  once (`mcp/ParentNoteStore.kt`). `start smith` puts every note of the
  split's (its root flow, the feature instance) in a `parent` run's brief as
  `parentNotes`, oldest first, each `{run, notedAt, note}`; a split with none
  gets an empty list. The same brief carries `deadline`: the run's start plus
  `engine.smith.k8s.active-deadline`. Neither reaches any other mode's brief,
  nor a child.
- **Acceptance is the parent's**: after the last child the parent runs
  `check acceptance`, in either mode, against its own
  original `plan.acceptance`; the acceptance and the close say `Merged
  children #a (PR #x), #b (PR #y)`, and `; closed with nothing to do: #c`
  for satisfied children.
- **Depth 1.** A design run on a ticket whose body carries the part line is
  told it may not return `split`; one that does anyway is refused by
  `readResult` with an incident naming the depth limit, before any GitHub
  write. No flow state tracks "this is a child": the part line in the
  approved body is the marker.
- **A child that splits** (#1571). A child's design may not split it again,
  but its work Smith may find, once in the code, that it is too big for one
  run or holds parts worth merging on their own. The work brief of a child
  carries `parent` (the number its part line names), and only then may the
  work result be `split`: `children`, 2-16 drafts in the design's shape
  (checked by the same `ChildDraft.problems`), a `summary`, no commits and no
  open findings. On a ticket with no parent `readResult` refuses it as a
  broken result (a `rules` finding, then an incident); that Smith asks the
  user instead, as before. The `split` branch of the chore, bug and feature
  flows is `close ticket, split` (`${closeSplit}`, `task-close-split`). It
  lists the drafts on the child and closes it as `not_planned`, and hands
  the drafts to the parent by the parent's state (#2593):
  - **Parked on this child** (its `engine.deferred_issue` row's
    `waiting_instance` lists it: `serial`, `parallel`, or `live` after a
    fallback): it correlates the parent's `child closed` with
    `closedChildSplit` (the drafts) and deletes the row, in the same
    transaction. The scan never sees the row again, so the parent is woken
    once, by this, never also as a child closed without a merge.
  - **Running, not parked on it**: a `live` parent whose Smith watches the
    children (it parks nothing), or a `serial`/`parallel` parent between a
    replan and its next park. The child closes alone, and its drafts stay
    in its flow's `work` result (`FlowStarter.splitDrafts` reads them from
    history). Whatever records the close hands them over: the scan's wake
    once the parent is parked on the closed child again (`childSplit`
    instead of `childClosed`), or, live, `reconcile the children` after the
    parent run. The live parent Smith sees the child closed `not_planned`
    through `await_children`, with no change to its contract; the engine
    files the drafts once its run ends (with every child closed, after
    applying its result; with children open, at the fallback's
    `file the next child`).
  - **No flow on the parent** (cancelled or ended): an incident naming the
    parent's state and what a human does (file the drafts, in the child's
    `work` result, by hand, then cancel the child's flow); nothing is
    closed.

  `task-close-split` is one of `mergedClose`'s closes, so the split child is
  no stall when a later sibling's merge wakes the parent. The replan brief's `split` carries `closedChildSplit`; the replan
  and `apply the replan` record the child as `split` (neither merged nor
  satisfied) and put the drafts in its place, at the front of the remaining
  drafts (`SplitState.splitInto`: their `dependsOn` renumbered over the whole
  split, later indexes moved past them, a later draft that waited on the
  child waiting on its last part), before `continue`, `revise` or `done`
  applies. `file the next child` then files them as any other draft: in
  order, blocked via `Tickets.block`, and started `authorized by @x
  via parent #N` with no intake. A design run's own split is unchanged. A
  work Smith never files a split through `submit_ask`: those intakes wait
  for a trusted login, lose the parent's authorization and race the parent's
  replan (#1561).
- **Overflow re-entry.** `collect result` reads the run log for a stream-json
  `result` event with `terminal_reason` `blocking_limit` or a result saying
  `Prompt is too long` (`overflowHit`; an assistant line quoting the phrase
  does not count) and sets `overflowed`. A failed work run with that flag in
  a flow that has a plan gets outcome `overflowed` instead of an incident:
  `note the overflow` (`${noteOverflow}`) comments it and the flow re-enters
  `design` with `overflowed: true` in the brief and a prompt note that the
  ticket should split. A chore or bug has no design to return to and fails as
  before. Auto-compaction runs in `claude -p` on its own, so this path is the
  backstop for a plan that is too big even when compacted; every overflow up
  to 2026-09-25 was instead compaction failing on a read-only `.claude`
  ("Smith runs").
- `GET /issues` shows a parent's `children` (filed with number, state and
  PR; pending drafts with title only; dropped titles) and a child's `parent`;
  the desktop's Issues view and issue detail draw both.

## Private documents

A design doc or spec the user does not want in a public target repo's git history
(#1711: the codefrak-oss/hitlp design, kept until then as a claude.ai artifact a
Smith could not fetch) is stored in the engine's database against that repo,
`engine.repo_doc` (`repos/RepoDocs.kt`; deleted with the repo), and read by the
repo's Smiths. The engine never writes it to GitHub, and `bots/smith.md` ("Private
documents") tells a Smith not to quote it into an issue, PR, commit or file.

- **Add or update** (the user, with a named login; a Smith sandbox is refused every
  call here, reads too, with a 403):

  ```bash
  curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -X PUT "$ENGINE_URL/repos/codefrak-oss/hitlp/docs/design" \
    -H 'Content-Type: text/markdown' --data-binary @design.md
  ```

  The body is the doc's whole text (at most 512 KiB of characters, not blank); the
  name is 1 to 100 letters, digits, `.`, `_` or `-`. 201 when new, 204 when it
  replaced the doc by that name; 404 for a repo that is not a target repo, 401
  without a token. `GET /repos/{o}/{n}/docs` lists them (`name`,
  `chars`, `updatedAt`, `updatedBy`), `GET .../docs/{doc}` answers one's text,
  `DELETE .../docs/{doc}` (with a login) removes it. For a claude.ai artifact, copy
  its text into a file and PUT that.
- **How a Smith reads them.** Every brief of a run on the repo (refine, design,
  work, review and the rest) lists them in `privateDocs` (`name`, `chars`; never the
  text), and the frakworks MCP server's `list_private_docs` and
  `read_private_doc({name, offset?})` answer them for the run's own repo only ("The
  frakworks server in a Smith"). First mate reads any target repo's with the same
  tools and a required `repo`.

## First mate's MCP servers

Every First mate and every Smith run gets the engine's three MCP servers (`hitlp`,
`frakworks` and `frakworks-engine-diagnostics`). A target repo can add its own
(#1801): the desktop writes them into the First mate's `frakworks-mcp.json` beside
the three when it spawns one for that repo, and allows their tools
(`mcp__<name>__*`; observability/desktop/README.md, "First mate"); a Smith run of
the repo gets the ones meant for it ("The frakworks server in a Smith", "A Smith's
repo MCP servers", #2449). They are rows of `engine.repo_mcp_server` (V65, V81;
`repos/RepoMcpServers.kt`; deleted with the repo), one per server per repo.

- **A server** has a `name` (1 to 64 letters, digits, `_` or `-`; not `frakworks`,
  `frakworks-engine-diagnostics`, `hitlp` or `config`), a `kind` (`http`, `sse` or
  `stdio`, the `type`s `claude --mcp-config` takes), an `audience` (below), an
  optional `role` ("The ops role", below) and the
  kind's fields: `url` (required, http or https) and `headers` for `http`/`sse`;
  `command` (required), `args` (a list of strings) and `env` for `stdio`. A field of
  the other kind, an unknown field, an unknown kind or audience, or a missing
  required field is 400 naming the field.
- **The audience** (V81, #2449) says who gets the row: `first-mate` (the desktop's
  First mate on the user's machine and a first-mate pod), `smith` (a Smith run of
  the repo, in its sandbox on the cluster) or `both`. It is required on every
  `PUT` (400 `audience is required: smith, first-mate or both (who gets the
  server)` without it, `audience is smith, first-mate or both, not '<x>'` for an
  unknown value), set in the modal's "Who gets it" select and by the tools' `audience`,
  and listed by `GET`. Rows from before V81 are carried over as `first-mate`, so no
  First mate lost a server and no Smith gained one by the migration. The First mate
  on the Mac cannot resolve cluster DNS and a Smith pod cannot reach the Mac's
  loopback, so one server is typically two rows: a laptop instance
  (`http://127.0.0.1:<port>/mcp`, `first-mate`) and a cluster instance
  (`http://<svc>.<ns>.svc.cluster.local:<port>/mcp`, `smith`), each with its own
  token. `GET .../config` and a first-mate pod (`FirstMateMcpConfig.forSession`)
  carry `first-mate` and `both` rows only; a Smith run carries `smith` and `both`.
- **Secrets.** A header or env value is a string (stored plain) or
  `{"value": "...", "secret": true}`, sealed with `ENGINE_SECRETS_KEY` (`SecretsKey`,
  associated data `<repo>#mcp/<server>/<field>/<key>`) as the repo token is;
  `{"secret": true}` alone keeps the value already sealed under that key. A read
  shows a secret only as `{"secret": true, "set": true, "since": "<instant>"}`, a
  plain one as `{"secret": false, "value": "..."}`. Without the key, a write that
  seals one is 503. The row's `headers` and `env` are JSON as TEXT, name to
  `{"secret": false, "value"}` or `{"secret": true, "sealed": "<base64>", "setAt"}`.
- **The API**, under `/repos/{owner}/{name}/mcp-servers`: `GET` lists them (name
  order: `repo`, `name`, `kind`, `url` and `headers` or `command`, `args` and `env`,
  `updatedAt`, `updatedBy`), `GET .../{server}` one, `PUT .../{server}` adds or
  replaces it whole (201 new, 200 replaced; the token's login is `updatedBy`),
  `DELETE .../{server}` removes it (204, 404 when there is none), `POST
  .../{server}/test` tests it, and `GET .../config` answers
  `{"mcpServers": {<name>: <entry>}}` with the secrets opened, the First
  mate-audience rows only, for the desktop's spawn alone. 404 for a repo that is not a target repo. The list is open; a Smith
  sandbox gets 403 on every write, the test and the config (by source address, as
  `PUT /repos/{owner}/{name}/refactor`; 503 when Docker cannot say). A write puts a
  `repo` frame `changed` with field `mcpServers` on the stream.
- **The test** (`McpProbe`) answers `{reached, tools, toolSpecs, error, contract?}`: an MCP `initialize`,
  `notifications/initialized` and `tools/list`, 10 s each; `toolSpecs` is each
  listed tool's `name`, `description`, `inputSchema` and `outputSchema` as the
  server sent them, and `contract` is the role's report ("The ops role"), absent
  for a row without one. An `http` server is spoken
  to as streamable HTTP (POSTs, a JSON or event-stream answer, `Mcp-Session-Id`
  carried on); an `sse` server as the HTTP+SSE transport (a GET stream whose
  `endpoint` event names where to POST). A `stdio` server runs on the user's
  machine, so the engine answers `reached: false` with the error `a stdio server
  runs on the user's machine: the engine cannot test it from the cluster, the
  desktop does`, and the desktop's Configure First Mate modal spawns it and does
  the handshake itself.
- **From First mate**, the frakworks server's `read_repo_mcp_servers({repo})`
  (each row with its audience: `- ops-server (http, smith): …`),
  `add_repo_mcp_server` and `edit_repo_mcp_server({repo, name, kind, audience, url?,
  headers?, command?, args?, env?})` (add refuses a name taken, edit one missing),
  `remove_repo_mcp_server({repo, name})` and `test_repo_mcp_server({repo, name})`
  do the same in-process.

### The ops role

A row may carry the role `ops` (V96, #100008; `role VARCHAR(20) NULL CHECK (role
IN ('ops'))`, the design of #100007, part A): the server that answers "is commit
X in production for this repo?", which `DeployedChecks` asks ("Deploys" in
engine/README.md) and the post-deploy check of #100007's later parts builds on.
Rows from before V96 carry no role.

- **The contract, `ops/1`** (`repos/OpsContract.kt`): one tool, `is_deployed`,
  whose `inputSchema` takes `sha` as a required string, answering
  `{state: "deployed"}` or `{state: "not_yet"}` in its `structuredContent`, or as
  JSON in its first `text` content. `deployed` means the sha is the commit running
  in production or an ancestor of it: after a merge the default branch moves on,
  so the running commit is rarely the merge sha itself. The answer shape is
  named either by an `outputSchema` whose `properties.state.enum` holds both
  states, or by a `description` that mentions both `deployed` and `not_yet`.
  `OpsContract.conformance(toolSpecs)` answers `{version: "ops/1", conforms,
  missing: [...]}`, each missing line saying which of the three is not met (the
  tool, the `sha` argument, the named answer).
- **Setting it.** `PUT .../{server}`, `add_repo_mcp_server` and
  `edit_repo_mcp_server` take `role: "ops"`; any other value is 400 `role is ops
  or absent`, and a body without it stores no role (an edit without it drops
  one). An ops row is `http` or `sse`: the engine calls it from the cluster, so a
  `stdio` row with the role is 400 (`a stdio server cannot have role ops: ...`).
  A repo has one ops row per audience (400 naming the other), so the laptop and
  cluster instances of one ops server are two rows as any server's are; the
  engine itself uses the cluster-reachable one (`smith`, then `both`, then
  `first-mate`; `RepoMcpServers.opsServer`).
- **Checked before it is stored.** A put, add or edit with the role probes the
  server first (`RepoMcpServerController.opsCheck`, through
  `RepoMcpServers.put`'s check hook, with the secrets of the body opened) and is
  refused 400 `ops server does not conform to ops/1: <missing; missing>` when the
  tool is absent, `sha` is not a required string, the answer shape is not named,
  or the server cannot be reached (the probe's error is then the missing line).
  Nothing is written on a refusal. `GET` lists `role` (`"ops"` or null), and
  `read_repo_mcp_servers` shows it as `- ops-server (http, smith, role ops): …`.
- **The test report.** For an ops row, `POST .../{server}/test` and
  `test_repo_mcp_server` carry the contract: `contract: {version: "ops/1",
  conforms: true, missing: []}` on the JSON, and `; it conforms to ops/1` or `;
  it does not conform to ops/1: <missing>` after the tool list in the tool's
  text. The desktop's MCP server modal has a `Role` select (None, Ops; disabled
  for a stdio row) and shows the contract after the test result.
- **Asking it.** `OpsContract.isDeployed(config, sha)` does the handshake and one
  `tools/call is_deployed {sha}` (`McpProbe.call`) and reads `state`; an `isError`
  result, no structured or JSON text content, or a state that is neither is an
  error naming the server. `DeployedChecks.isDeployed(repo, sha)`
  (`deploy/DeployedChecks.kt`) is the engine's one answer: for its own repo
  (`engine.build.repo`) it needs no server, `deployed` when `sha` is
  `engine.build.commit` or GitHub's compare of `sha...commit` (`GitHubCompare`,
  the repo token) says `ahead` or `identical`, `not_yet` otherwise; for any other
  repo it asks the ops row, and without one throws `NoOpsServer`, whose message
  names `add_repo_mcp_server(repo, role: "ops", ...)` and the `ops/1` contract.

## Answer attachments

When a Smith asks the user a question (the desktop's yellow answer box, a `/needs`
`question` row), the user can attach files to the answer with the box's paperclip
(#1730). The desktop uploads each file before it posts the answer, and posts the
answer only when every upload was stored.

- **Upload** (one file per call; no Smith sandbox (403), and
  the token's login must be trusted):

  ```bash
  curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -X POST "$ENGINE_URL/intake/<flow id>/attachments" -H 'Content-Type: application/json' \
    -d "{\"name\":\"spec.md\",\"mime\":\"text/markdown\",\"content\":\"$(base64 -w0 spec.md)\",\"by\":\"<login>\"}"
  ```

  `{id}` is the flow's id, the `/needs` row's `actionId`, as for the answer. It
  answers `{intakeId, name, mime, size}`; a second upload by the same name replaces
  the first. 409 when the flow waits on no question; 400 for a bad name (1 to 100
  letters, digits, spaces, `.`, `_`, `-`) or content that is not base64.
- **Types and caps** (`AnswerAttachments`). Text in UTF-8 (Markdown, code, JSON…;
  stored as the given `text/*` or JSON/YAML/XML/TOML type, else `text/plain`) and the
  images `image/png`, `image/jpeg`, `image/gif` and `image/webp` are taken. Any other
  binary is a 415 naming what is taken. A file is at most 1 MiB and a flow's files 5
  MiB together: past either, a 413 naming the cap. The desktop checks both caps
  before it reads a file.
- **Storage.** `engine.answer_attachment` (V62: `flow`, `name`, `mime`, `size`,
  `content` as `bytea`, `created_at`, `created_by`), keyed by the root flow of the
  flow that asked, in the database, never a pod's filesystem (the engine runs two
  replicas). The engine never writes an attachment to GitHub: the answer's comment
  carries the text alone.
- **How a Smith reads them.** Every brief of a run in that flow after the upload
  lists them in `attachments` (`name`, `mime`, `size`; never the content), and the
  frakworks MCP server's `list_attachments` and `read_attachment({name, offset?})`
  answer them to that flow's runs only ("The frakworks server in a Smith").
  `bots/smith.md` ("Answer attachments") tells a Smith not to quote one into an
  issue, PR, commit or file.

## The brief: what a Smith is told

Once there is a ticket snapshot, a PR or a finding, the engine puts
`/work/in/brief.json` in the container before starting it (`Brief`), and
the prompt carries it; a security review (of the round just pushed), and
the review of a proposal's unpushed draft, and every propose run after the
first (the latest draft, pushed or not, which `run-smith.sh` checks out as
`smith/issue-<n>` on top of the PR branch or the default branch), also get
`/work/in/commits.bundle`:

```json
{"round": 1,
 "ticket": {"number": 5, "title": "…", "body": "…", "author": "…"},
 "pr": {"number": 7, "url": "…", "branch": "smith/issue-5"},
 "findings": [{"id": "review-3-1", "review": "correctness", "ask": "…",
               "severity": "high|medium|low", "file": "…", "line": 3}],
 "raised": [{"id": "security-1-1", …}, {"id": "review-3-1", …}],
 "work": {"outcome": "done", "title": "…", "summary": "…", "addressed": […], "declined": […]}}
```

A `fix` or `diagnose` run (see "Fix and Propose a fix") gets `"incident"`
instead of a ticket: the incident's `id`, `activityId`, `activityName`,
`type`, `message`, `processInstanceId`, `processDefinition`,
`rootInstanceId` and `rootFlow`, a failed job's `stacktrace` (at most 8 KB) and `attempts` ("Incidents"),
and `variables: {"root", "failing"}`, the plain variables of the ticket's
flow and of the process that failed (`output`, `logTail` and `work` decoded,
at most 8 KB each).

A run on a ticket the user amended after it was filed ("Intake", "A follow-up
to a ticket") also gets `"amendments": [{"by", "at", "text"}]`, every
amendment so far, oldest first (process variable `amendments`, carried as
`answers` is); the ticket's `body` carries them too, in its `## Amendments`
section. A Smith treats them as binding as the ticket (`bots/smith.md`).

In the proposal flow every run also gets `"proposal": {"path":
"docs/proposals/<slug>.md", "summary": "…"}`, the last draft, once there is
one ("Proposal").

A correctness review (mode `review`) after a passing engine check whose tests
needed a retry also gets `"ci": {"check", "outcome", "url", "flaky": ["<Class>
> <method>()"]}` (process variable `ciFlaky`, removed by every push;
engine/README.md, "Flaky tests"): the reviewer makes one a finding only if the
branch changed its code or plausibly caused its flakiness. No other run is
told, and the field is absent when no test needed a retry.

A security run on a clean merge of the default branch ("Bringing the PR up
to date") also gets `"merge": {"default": "main", "oldHead": "<the branch
before>", "merge": "<the merge commit>"}`; no other run is told.

A run whose ticket (or the answers it was given) names an earlier intake,
by its intake id or its ask item `#N`, also gets `"intakes": [{"ref",
"intake", "repo", "issue", "state", "ticket", "ended", "ask"}]`, re-resolved at
each Smith start from the intake's history: `state` is `filed` (with the
`ticket` it filed), `refining` or `ended` (with how), and `ask` is the
intake's ask text in every state, cut to 2 000 characters
(`IntakeRefs.ASK_CHARS`) with `…` appended when cut; null only when the
intake has no ask. The run's own intake
is left out. A refine uses it to write the ticket's dependencies, and
every other mode reads `#N` as `#T` and asks rather than pass a dependency
still `refining` or `ended` ("Blocked by", "An earlier intake").

`findings` are the ones the last review sent back, with the ids and review
names the engine gives them (`<mode>-<review number>-<n>`, e.g.
`security-1-1`, `rebase-<conflict round>-1` for a merge conflict, or
`ci-<round>-1` (review `ci`) for a failed engine check, whose ask carries the
failing tests and the end of the job log); a work Smith must answer every one. `raised` is every
finding so far, so a reviewer can check what `work` claims to have
addressed or declined. The brief is only ever in the sandbox: `raised` holds
security findings, so it must not be posted anywhere. `ticket` is the
issue as it was approved; a Smith with one works from it alone and does not
read the issue or its comments from GitHub (without one, which only the
tests do, it reads the issue itself).

## Lineage

Every Smith run's brief carries `lineage`, the HITLP 0.2 chain of intent
(codefrak-oss/hitlp `spec/hitlp.md` §5.3) the run works under, and every
first-mate session gets one too. The engine is the system that holds the links:
it writes the lineage once, at the start of the work, and the agent passes it
unchanged to any MCP server that takes one (the ops server's
`open_session` and `run` first; `bots/smith.md`, "Lineage: pass it through
unchanged"; `shared/first-mate-prompt.json`). Built by `Lineage`
(`engine/modules/smith`).

```json
{"asOf": "2026-10-11T10:00:00Z", "truncated": false, "links": [
  {"kind": "ticket", "claim": "…", "source": {"repo": "owner/name", "number": 20, "bodyRev": "sha256:…"}, "by": "alice"},
  {"kind": "intake", "claim": "…", "source": {"intake": "<id>", "filedBy": {"run": "<run id>", "repo": "owner/name", "number": 10}}, "by": "smith:<run id>"},
  {"kind": "ticket", "claim": "…", "source": {"repo": "owner/name", "number": 10, "bodyRev": "sha256:…"}, "by": "alice"},
  {"kind": "intake", "claim": "…", "source": {"intake": "<id>", "filedBy": "alice"}, "by": "alice"}]}
```

- **A ticket link** is built from the run's `approvedTicket` snapshot (and, up
  the chain, the `approvedTicket` on the root flow of the run that filed the
  intake): `claim` is the first sentence of its `## Goal` (its title without
  one), `by` the login in its engine trailer (`· by <login> ·`) only when
  `engine.intake_ticket` records an intake that filed it (the engine wrote the
  trailer), else the issue's author: a hand-filed ticket's trailer is an HTML
  comment anyone can write. `bodyRev` is `sha256:<hex>` of the body as captured.
- **An intake link** comes from `engine.intake_ticket` (the intake that filed
  the ticket, or whose own issue it is, for a refine) and the intake's history:
  `claim` is the first sentence of its `ask`; `filedBy` is the `submittedBy`
  login, or, when `requestedByRun` is set, `{run, repo, number}`, the Smith run
  and the ticket it worked. An intake with neither recorded says `"unknown"`,
  never an invented filer. The chain then continues with that run's ticket.
- **A session link** (first mate): `{kind: "session", claim: the chat's ask,
  source: {login} (and `session`, the phone session's id), by: login}`.
- **The cap**: at most 8 links; a longer chain is cut to its first 8 and
  `truncated` is `true`. A cycle (an intake seen twice) ends the chain.
- **Captured once.** The first run on a ticket builds it and keeps it as the
  flow's `lineage` variable; every later run is handed the same snapshot, `asOf`
  included. A ticket amended since shows against the captured `bodyRev`: hash
  the live body (`printf %s "$body" | sha256sum`) and, when it differs, diff the
  brief's `ticket.body` (the snapshot the hash is of) against it.

Where an agent finds it, and why it cannot alter it:

- **A Smith**: `/work/in/brief.json`'s `lineage`. `/work/in` is the engine's
  input, rewritten from the flow at every run, so nothing a run edits there
  reaches the next.
- **A phone first-mate session**: built at `POST /first-mate/sessions` from the
  body's optional `ask` (the phone sends its first message with it; without one
  the claim is `First-mate session on <repo>.`) and the token's login, stored on
  `engine.first_mate_session.lineage` (V80), and put in the session's Secret as
  `lineage.json`, mounted read-only at `/etc/firstmate/lineage.json` in every
  pod the session gets; `FM_LINEAGE_FILE` names it, and the pod's
  `--append-system-prompt` ends with a `Lineage file: <path>` line telling the
  session to open that literal path with the `Read` tool, never Bash (#2496: a
  shell read of the variable is refused by the permission check).
- **The desktop's First mate**: `POST /first-mate/lineage {ask}` with the
  signed-in token (any login's; no new token purpose) answers a one-link
  session lineage; the app asks once per conversation, with its first ask,
  writes the answer as received to a read-only `first-mate/lineage.json` in its
  data folder and starts the child with `FM_LINEAGE_FILE` naming it and the
  same `Lineage file: <path>` line (open with `Read`) at the end of its
  appended system prompt.

The engine sends the lineage it builds under `asserted.lineage` and serves no
HITLP itself (#2484): R10 (store it as received, never resolve, check or extend
it) binds the HITLP server it files on.

## What each mode writes

`readResult` checks `result.json` by the run's mode before anything is
written to GitHub; a broken rule is an incident, except a work result's first
one, which reruns the round on a `rules` finding ("Incidents", "A rejected work
result").

| mode | `result.json` | rules |
|---|---|---|
| `work` | `{"outcome": "done", "title", "summary", "addressed": [{"finding", "how"}], "declined": [{"finding", "why"}]}`, `{"outcome": "nothing-to-do", "reason"}` or `{"outcome": "ask", "question"}` | `done` needs a title, a summary, a bundle, and every open finding addressed or declined; `nothing-to-do` needs a reason, and no bundle and no open findings; a `rules` finding is not counted. The first rejection in a row is the outcome `rejected` and a rerun, not an incident ("Incidents") |
| `review` | `{"outcome": "approved" \| "changes", "summary", "findings": [{"ask", "severity", "file", "line"}]}` | `changes` needs findings, `approved` has none |
| `security` | the same, with `passed` \| `findings` | the same |
| `refine` | the intake's `RefineResult` (above) | as above |
| `design` | `{"outcome": "planned", "plan": {"summary", "files", "stages": [{"title", "detail"}], "tests", "acceptance"}, "questions": [{"question", "assumption", "confidence", "impact", "options"}]}` (`options` optional: `[{"id", "label"}]`), the same with `"outcome": "split"` and `"children": [{"title", "body", "kind", "dependsOn", "points"}]`, or `{"outcome": "ask", "question"}` | `planned` and `split` need a plan with a summary and at least one acceptance criterion; every question needs an assumption, a confidence 0–1 and a known impact, and its `options`, when given, 2 to 5 with unique non-empty ids and non-empty labels; `split` needs 2-16 children with known kinds, points 1–13, dependencies on earlier children only, and is refused from a child ticket ("Split features") |
| `replan` | `{"outcome": "continue" \| "revise" \| "done", "children": […], "summary"}` | `revise` needs children, validated like a design's, and filed plus revised at most 16; each revised child, and each remaining draft a `continue` leaves, has a Goal with content and Acceptance criteria with at least one item, and does not restate a filed child (its title, or a body that is its record: `Filed as #n` / `merged in PR #x` with no work of its own; naming a merged sibling as a dependency is fine) |
| `accept` | `{"outcome": "accepted" \| "unmet" \| "unverified", "summary", "criteria": [{"criterion", "met", "how"}]}` | `unmet` needs a criterion with `met: false`; `accepted` has none false and none null |
| `research` | `{"outcome": "answered", "summary", "answer": {"md", "html"}, "questions": [...]}` or `{"outcome": "ask", "question"}`; the result is kept as `research` | `answered` needs a non-blank `answer.md`, an `answer.html` passing `PitchRules`, and a summary; a run that left a bundle is refused: a research run commits nothing. Run by `bpmn/research.bpmn` ("Research") |
| `propose` | `{"outcome": "drafted", "path", "pitch", "summary", "questions": [{"question", "assumption", "confidence", "impact"}]}` or `{"outcome": "ask", "question"}`; the result is kept as `proposal` | `drafted` needs a `path` of one doc, `docs/proposals/<slug>.md`, a `pitch` of its page, `docs/proposals/<same slug>.html`, a summary, questions as a design's, and a bundle; the bundle (read through `GitPush.proposalChanges`, which pushes nothing) touches only `docs/proposals/` against the default branch, changes and keeps `path` and `pitch`, and on a revisit (the doc is on the default branch) keeps every line of its `Decision log:` list; the pitch's text at the head passes `PitchRules` (no `<script src>`, `<link>`, `@import`, remote `url()`, or remote `src`/`srcset`/`href` except `<a href>`; an inline `<svg>`, a `prefers-color-scheme: dark` rule and a viewport meta tag). Optional `options: [{id, label}]`, the options the pitch lets the user pick ("Picking an option" under "Proposal"): each id one letter `a`–`z`, unique, each label non-blank, and `PitchRules` then wants, for each id, a `data-option-id="<id>"` block and a disabled `<button data-choose="<id>">` placeholder in the page, and no such marker for an id not listed. A `drafted` with an important question (confidence below 0.7 or impact high) reads as `ask`, as a confirm-mode design does. Run by `bpmn/proposal.bpmn` ("Proposal") |
| `fix` | `{"outcome": "retry" \| "unfixable", "diagnosis", "summary"}` | a diagnosis; no bundle. The engine applies the remedy (`FixResult`) |
| `diagnose` | `{"outcome": "proposed", "summary", "diagnosis"}` | a summary, which the engine keeps on one line and cuts to 40 characters (its first 39 and `…`); no bundle (`DiagnoseResult`) |
| `doctor` | `{"outcome": "healthy" \| "unfixable" \| "retry" \| "collect-exit" \| "run-job", "target", "diagnosis", "defect": {"where": "engine" \| "target" \| "none", "summary"}, "summary"}` | a diagnosis; a `target` for `retry` (the incident id) and `run-job` (the job id); a `defect` for every outcome but `healthy`, never `where: none` on a remedy the engine applies; no bundle. The engine applies the remedy (`DoctorResult`, "Doctor") |
| `job` | `{"outcome": "done" \| "failed", "summary"}` | a summary; no bundle. The JSON is `callable-smith`'s `result` ("Custom jobs and the SDK") |

## After the merge: the post-deploy check

When the PR has a `## POST-DEPLOYMENT-CHECK` section, the feature flow does not end at its merge: it calls
`post-deploy-check` (`call-post-deploy-check`), which waits for the merge to reach production, runs check
Smiths until one passes or fails, asks the user at the deadline, and closes the ticket. See
[post-deploy-check.md](post-deploy-check.md).
