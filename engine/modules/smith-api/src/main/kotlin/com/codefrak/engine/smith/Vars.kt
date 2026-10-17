package com.codefrak.engine.smith

/** Process variables of `smith-run` (bpmn/smith-run.bpmn). */
object Vars {
    /** The ticket an intake filed, on the intake's instance (`intake`'s `FILED_TICKET`). */
    const val FILED_TICKET = "filedTicket"
    const val REPO = "repo"; const val ISSUE = "issue"; const val MODEL = "model"; const val EFFORT = "effort"
    /**
     * Who asked for the ticket a root flow works (engine/README.md, "Intake", `by`): the trailer's `by <login>`, else the
     * issue's GitHub author; set at the start of a chore, bug, feature or proposal (`ProcessFlowStarter`). A child the flow
     * files (a split's part, a proposal's build) inherits it in its trailer. An intake carries `submittedBy` instead.
     */
    const val ASKED_BY = "askedBy"
    /**
     * A mode `job` run's job id and prompt (engine/README.md, "Custom jobs and the SDK"): `callable-smith` sets them, and
     * the run has no [ISSUE]. `PROMPT` equals `CallableSmith.PROMPT` (the sdk is not a dependency of engine-smith).
     */
    const val JOB_ID = "jobId"; const val PROMPT = "prompt"
    /** The tier (the engine's tier map, `TierTable`) `MODEL`/`EFFORT` were resolved from; null when neither a ticket tier nor a default was found. */
    const val TIER = "tier"
    const val DRY_RUN = "dryRun"; const val HANDLE = "sandboxHandle"
    /** The sandbox's exit code: set by the `message-smith-exited` correlation (`SmithExitNotifier`). */
    const val EXIT_CODE = "exitCode"; const val LOG_TAIL = "logTail"; const val LOG = "log"; const val RESULT = "result"
    /** The Smith's structured output (`OUTPUT_PATH`), as bytes: it can pass a String variable's 4000. */
    const val OUTPUT = "output"
    /** A caller's pin: the `engine.ai_account` its runs must bill to. `start smith` only reads it, never writes it (#178). */
    const val ACCOUNT = "account"
    /**
     * The `engine.ai_account` this run bills to: the pin, else the picker's pick. Null only on a dry run with no
     * usable account. It is copied back to the calling flow like every smith-run variable, but no run reads it as a pin.
     */
    const val BILLED_ACCOUNT = "billedAccount"
    /** The sandbox's peak memory in bytes (`MEMORY_PEAK_PATH`); null when it could not be read. */
    const val PEAK_MEMORY = "peakMemoryBytes"
    const val MODE = "mode"; const val FAKE_OUTCOME = "fakeOutcome"
    /** A work or propose Smith's commits (`BUNDLE_PATH`), as bytes; null when the run left none. */
    const val BUNDLE = "bundle"
    /** The sandbox's usage-meter reading (`METER_PATH`), as bytes; null when the Smith made no Anthropic call. */
    const val METER = "meter"
    /** The approved ticket (`Ticket`), as JSON bytes: a body can pass a String variable's 4000. */
    const val TICKET = "approvedTicket"
    /** The run's HITLP lineage (`Lineage`), captured at the ticket's first run and handed to every later one unchanged. */
    const val LINEAGE = "lineage"
    /** The rounds a flow has pushed so far, its PR number and url, and the branch it pushes to. */
    const val ROUND = "round"; const val PR = "pr"; const val PR_URL = "prUrl"; const val BRANCH = "branch"
    /** The open findings of the last review, as JSON bytes (`Finding`s); null when none are open. */
    const val FINDINGS = "findings"
    /** Every finding any review has raised in this flow, as JSON bytes. */
    const val RAISED = "raised"
    /** The last work Smith's result.json, as bytes: its title, summary, and what it addressed. */
    const val WORK = "work"
    /** An intake's answered questions (`Answer`s), as JSON bytes, for the brief; `engine.intake_question` is the record. */
    const val ANSWERS = "answers"
    /** The user's late additions to a filed ticket (`Amendment`s, `TicketAmender`), as JSON bytes, oldest first. */
    const val AMENDMENTS = "amendments"
    /** A split parent's replan steers on this child's root instance (`SteerEntry`s, FlowStarter.steer), as JSON bytes, oldest first. */
    const val STEER = "steer"
    /**
     * An intake's duplicate check (`DuplicateCheck`): the line the refine brief carries when Jev matched the ask to
     * an open ticket ("this looks like #n"), that ticket's number, the `engine.jev_call` id, and the question put to the
     * user; all null otherwise.
     */
    const val DUPLICATE = "duplicate"; const val DUPLICATE_OF = "duplicateOf"; const val DUPLICATE_CALL = "duplicateCall"
    const val DUPLICATE_QUESTION = "duplicateQuestion"
    /** A bug's reproduce Smith's result.json (`ReproduceResult`), as bytes: in every later brief. */
    const val REPRODUCTION = "reproduction"
    /**
     * A bug's reproduce run's [REPRO_DIR], as Docker's tar of it: kept by `collect result` on a `reproduced` or
     * `understood` run that left files there, and unpacked to /work/in/repro for the fix and verify runs.
     */
    const val REPRO_FILES = "reproFiles"
    /** Why a reproduce run's [REPRO_DIR] was dropped (past [REPRO_MAX_BYTES]); null when it was not. Read result puts it in the reproduction. */
    const val REPRO_DROPPED = "reproDropped"
    /**
     * True on a flaky-test ticket's bug flow, set by `FlakyTests` alone through `AuthorizedStart` (engine/README.md,
     * "Flaky tests"): bug.bpmn skips the reproduce and verify runs, the brief's `flakyTest` tells the work run, and the
     * close says the fix was optimistic.
     */
    const val FLAKY_TEST = "flakyTest"
    /** A bug's verify Smith's result.json (`VerifyResult`), as bytes. */
    const val VERIFICATION = "verification"
    /** A feature's mode, `auto` or `confirm` (`FeatureMode`), from its `feature:<mode>` label or its intake. */
    const val FEATURE_MODE = "featureMode"
    /**
     * A split feature's child-work mode, `serial`, `parallel` or `live`: the repo's `child_mode` frozen on the feature
     * instance when its design split, so later runs read it here, never from the repo row mid-split (engine/README.md,
     * "Split features"). Unset until a design splits.
     */
    const val CHILD_MODE = "childMode"
    /** A feature's design Smith's plan (`Plan`), as JSON bytes: in every later brief. */
    const val PLAN = "plan"
    /**
     * The `DesignResult` whose important questions a confirm-mode feature put to the user, as JSON bytes: what
     * `GET /ticket/{owner}/{repo}/{n}/design-deck` renders (`DesignDeck`). Never set in auto mode.
     */
    const val DESIGN_DECK = "designDeck"
    /** A feature's acceptance (`AcceptResult`), as bytes: the accept Smith's, or the user's answer before #583. */
    const val ACCEPTANCE = "acceptance"
    /** A split feature's state (`SplitState`), as JSON bytes: the drafts left and the children filed. */
    const val SPLIT = "split"
    /** The last propose Smith's result (`ProposeResult`), as bytes: the doc it drafted, its summary and questions. */
    const val PROPOSAL = "proposal"
    /**
     * A proposal's decision (bpmn/proposal.bpmn, `task-read-decision`): the verb (`DecisionVerb`), the
     * reason given with it (null when none), and the build ticket filed by `accept and build`.
     */
    const val DECISION = "decision"; const val DECISION_WHY = "decisionWhy"; const val BUILT_TICKET = "builtTicket"
    /**
     * A decision given as structured data (a proposal decision's `accept` or `accept and build auto` on the HITLP server, `HitlpAnswers`): the verb and
     * its reason, set with the answer and taken by `task-read-decision` as they are, with no parse and no Jev; cleared
     * once read.
     */
    const val DECISION_CHOICE = "decisionChoice"; const val DECISION_CHOICE_WHY = "decisionChoiceWhy"
    /**
     * The HITLP task an ask wait was filed as (#2462): set by the posting delegate from `HitlpFiling.ask`'s handle,
     * and the §6 decision record it was completed with (`HitlpAnswers`), as JSON bytes.
     */
    const val HITLP_TASK_ID = "hitlpTaskId"; const val HITLP_RECORD = "hitlpRecord"
    /** The last replan Smith's result (`ReplanResult`), as bytes. */
    const val REPLAN = "replan"
    /** The last live parent Smith's result (`ParentResult`), as bytes. */
    const val PARENT = "parent"
    /**
     * A research flow (bpmn/research.bpmn): the last research Smith's result (`ResearchResult`) as bytes; the rounds
     * answered so far (0 before the first); the thread as bytes (a JSON list of `ResearchRound`: round, summary,
     * answer.md), which every later brief carries; whether `save` committed (`saved`), the commit it made, and
     * why a save path was refused (said on the next ack, then cleared).
     */
    const val RESEARCH = "research"; const val RESEARCH_ROUND = "researchRound"; const val RESEARCH_THREAD = "researchThread"
    const val SAVED = "saved"; const val SAVE_COMMIT = "saveCommit"; const val SAVE_REFUSED = "saveRefused"
    /** True when the last work run overflowed its context window (`overflowHit`); the next design run is told. */
    const val OVERFLOWED = "overflowed"
    /**
     * What `child closed` carries: the child's number and, when its close was a merge, its PR; when its work run
     * split it instead, the drafts (JSON bytes) that take its place (engine/README.md, "A child that splits").
     */
    const val CLOSED_CHILD = "closedChild"; const val CLOSED_CHILD_PR = "closedChildPr"; const val CLOSED_CHILD_SPLIT = "closedChildSplit"
    /**
     * A clean merge of the default branch, as JSON bytes (`default`, `oldHead`, `merge`), from `bring pr up to
     * date` through its push, engine check and security review until the next committing run clears it; only a
     * security run's brief carries it. Null otherwise.
     */
    const val MERGE = "merge"
    /** Merge conflicts sent back to a work round in a row, reset when a round resolves one; the third fails the flow. */
    const val CONFLICT_ROUNDS = "conflictRounds"
    /** Merge conflicts sent back to a work round over the flow's life, never reset (`ConflictRounds.MAX_TOTAL_CONFLICT_ROUNDS`). */
    const val CONFLICT_ROUNDS_TOTAL = "conflictRoundsTotal"
    /**
     * Conflict rounds a human's retry of a conflict-cap incident granted and the flow has not used yet (`IncidentRetrier`),
     * each exempt from the lifetime cap (`ConflictRounds`). Null or 0 without a grant.
     */
    const val CONFLICT_ROUNDS_GRANTED = "conflictRoundsGranted"
    /**
     * Pushes `open/update pr` refused because the bundle rewrote or lacked the branch, whose retry reran the work
     * (`IncidentRetrier`), over the flow's life, never reset: `<n>` in the rerun's `push-<n>-1` finding.
     */
    const val PUSH_REJECTIONS = "pushRejections"
    /** What `bring pr up to date` found, `current`, `clean` or `conflict`, for its gateway. */
    const val REBASE = "rebase"
    /** Set by the boundary event on `merge pr` when GitHub refused the merge for conflicts. */
    const val MERGE_ERROR = "mergeError"
    /** The head `open/update pr` pushed, for the engine check (engine/README.md, "Chore"). */
    const val HEAD_SHA = "headSha"
    /** The check the pushed branch waits for (`engine`), or null when its repo has none or it touches none of its paths. */
    const val CI_CHECK = "ciCheck"
    /**
     * The repo's `security_review` (engine/README.md, "Chore"), read by `open/update pr` at each push: false sends the
     * flow from the engine check straight to `review pr`. Unset (an instance pushed before the setting) counts as on.
     */
    const val SECURITY_REVIEW = "securityReview"
    /**
     * Set by open/update pr at each push: true when every path the branch changes against the default branch is
     * documentation (`DocsOnly`), so a chore, bug or feature takes the docs path, the engine check then the
     * correctness review with no security run (engine/README.md, "Docs path"). Unset or false: the full pipeline.
     */
    const val DOCS_ONLY = "docsOnly"
    /**
     * Set by open/update pr at each push: true when the branch changes engine/modules/hitlp/ or a hitlp migration
     * (`HitlpPaths`), so the approved PR waits at `user-task-answer-merge` for the user before the merge
     * (engine/README.md, "Hitlp changes wait for the user").
     */
    const val HITLP_CHANGE = "hitlpChange"
    /** The user's answer at `user-task-answer-merge`: true to merge; removed at every push, so each head is accepted on its own. */
    const val MERGE_ACCEPTED = "mergeAccepted"
    /** `reusable-ci-check`'s answer for its caller's gateway: `skipped`, `passed` or `failed` (`waiting` inside it). */
    const val CI_OUTCOME = "ciOutcome"
    /**
     * A passing check's tests that passed only on a retry, `<Class> > <method>()` one per line (engine/README.md, "Flaky
     * tests"); null when none, or when the check failed. The correctness review's brief carries them ([Brief.ci]); a push sets it null, and a later head ignores it.
     */
    const val CI_FLAKY = "ciFlaky"
    /** The head [CI_FLAKY] was read for: the brief carries the lines only while it is still [HEAD_SHA]. */
    const val CI_FLAKY_HEAD = "ciFlakyHead"
    /** The completed check run the wait was woken with: its conclusion, id (an Actions job's id) and page. */
    const val CI_CONCLUSION = "ciConclusion"; const val CI_RUN_ID = "ciRunId"; const val CI_RUN_URL = "ciRunUrl"
    /** The check run `read check` re-ran on Jev's triage, until the next push: its completed run wakes nothing. */
    const val CI_RERUN_OF = "ciRerunOf"
    /** The round of the last re-run: one per round (engine/README.md, "Chore"). */
    const val CI_RERUN_ROUND = "ciRerunRound"
    /** How many times this wait re-triggered a check that had not started, by closing and reopening the PR. */
    const val CI_RETRIGGERS = "ciRetriggers"
    /**
     * A work round that declined every engine-check finding as not from its change and committed nothing
     * ([WorkResult.ciNotMine]): `open/update pr` re-runs the failed check run instead of pushing new commits.
     */
    const val CI_NOT_MINE = "ciNotMine"
    /** How many such rounds ran in a row; a round with commits resets it, and past [CI_NOT_MINE_MAX] the result is refused. */
    const val CI_NOT_MINE_ROUNDS = "ciNotMineRounds"
    const val CI_NOT_MINE_MAX = 2
    /** The check run `open/update pr` re-ran for a [CI_NOT_MINE] round, carried into the next wait as [CI_RERUN_OF]. */
    const val CI_RERUN_PENDING = "ciRerunPending"
    /** The work round's bundle before this run's collect replaced it: a [CI_NOT_MINE] round keeps it for the reviews after the check. */
    const val PREVIOUS_BUNDLE = "previousBundle"
    /** The `engine.jev_call` id of the ci-triage call whose outcome is still to come: the re-run's, or the work Smith's diagnosis. */
    const val CI_JEV_CALL = "ciJevCall"
    /** The ci finding [CI_JEV_CALL] went out with, whose answer is that call's outcome. */
    const val CI_JEV_FINDING = "ciJevFinding"
    /** A fix or diagnose run's incident (engine/README.md, "Fix and Propose a fix"), as JSON bytes for the brief. */
    const val INCIDENT = "incident"
    /** A doctor run's stuck ticket (engine/README.md, "Doctor"), as JSON bytes for the brief. */
    const val STUCK = "stuck"
    /**
     * Set by `collect result` on every run (engine/README.md, "Incidents"): true when the run failed only on its
     * account's session or spend limit and another account of its provider is usable, now or at a known reset
     * ([RERUN_AT]), or when it ended failed or with no result and its step has not been rerun after a failure yet
     * ([FAIL_RERUN_OF]), so `reusable-smith-run` starts its call activity over instead of reading the result. False otherwise.
     */
    const val RERUN = "smithRerun"
    /**
     * Set by `collect result` on every run, with [RERUN] for a timed rerun: when the provider's first account gets back
     * in ([com.codefrak.engine.accounts.AiAccounts.blockedUntil]), a `java.util.Date` that `reusable-smith-run`'s
     * `event-wait-reset` timer waits for before the call activity starts over. Null for any other run, which does not wait.
     */
    const val RERUN_AT = "smithRerunAt"
    /**
     * Set by `collect result` on every run: what a session or spend limit did to it ("on account X's session or spend
     * limit, …"), for the rerun's issue comment and the incident message when it is not rerun; null when it hit none.
     */
    const val LIMIT_NOTE = "limitNote"
    /**
     * The accounts a step has been rerun after a session or spend limit on, comma-separated, kept on the caller (every
     * variable goes in and out of a run): each account reruns a step at most once. Cleared by a run that ends any other way.
     */
    const val LIMIT_RERUNS = "limitReruns"
    /**
     * The `reusable-smith-run` instance whose step was already rerun once after a failed run, kept on the caller like
     * [LIMIT_RERUNS]: its next failure parks on the incident. A later step's wrapper is a new instance, so it never matches.
     */
    const val FAIL_RERUN_OF = "failRerunOf"
    /** Set by `collect result` with [RERUN] for a failure rerun: how the run ended ("failed (exit 1)"), for the rerun's comment; null otherwise. */
    const val FAIL_NOTE = "failNote"
    /** Set by `collect result` with [RERUN] for an eviction rerun: how often the cluster evicted the step's runs, for the rerun's comment; null otherwise. */
    const val EVICT_NOTE = "evictNote"
    /**
     * How many runs of a `reusable-smith-run` instance the cluster evicted, as `<wrapper>:<n>`, kept on the caller like
     * [FAIL_RERUN_OF]: each eviction reruns the step until [MAX_EVICTION_RERUNS], independent of the one failure rerun.
     */
    const val EVICTION_RERUNS = "evictionReruns"
    /**
     * Set by `read result` when it turned a work result down as a `rules` finding and reran the round (outcome `rejected`);
     * cleared by the next accepted work result. Set, the next rejection parks on the incident (engine/README.md, "Incidents").
     */
    const val RULES_REJECTED = "rulesRejected"
    /**
     * The post-deployment check (engine/docs/smith-runs.md, "The check mode"; part C of #100007 wires it into the
     * flows): the PR's `## POST-DEPLOYMENT-CHECK` section (`PostDeployCheck`, JSON bytes: `what`, `recheck`,
     * `deadlineAfter`, `deadlineAt`), the merge commit's sha, and the deploy event that said the change is in
     * production (JSON bytes, the bus frame; null while none came).
     */
    const val POST_DEPLOY_CHECK = "postDeployCheck"; const val MERGE_SHA = "mergeSha"; const val DEPLOY_EVENT = "deployEvent"
    /**
     * Why the merge waits for the user before it goes ahead (`CheckPostDeployDelegate`): the PR's section cannot be
     * read, or the repo has no ops server to say when the merge is in production. Null when nothing blocks it.
     */
    const val POST_DEPLOY_BLOCKED = "postDeployBlocked"
    /**
     * What a `check` run answered (`CheckResultHandler`): its outcome (`passed`, `failed`, `not_yet`) and summary, when
     * to look again (`checkWakeAt`, a `java.util.Date` for the flow's timer, from the hint's `at` or `nextCheck`'s
     * ladder) or on which bus frame (`checkWakeEvent`, `<type>[:<field>=<value>]`), every run so far (`checkHistory`,
     * JSON bytes, a list of `CheckRun`, oldest first), and the bug a `failed` run filed (`fileCheckBug`).
     */
    const val CHECK_OUTCOME = "checkOutcome"; const val CHECK_SUMMARY = "checkSummary"
    const val CHECK_WAKE_AT = "checkWakeAt"; const val CHECK_WAKE_EVENT = "checkWakeEvent"
    const val CHECK_HISTORY = "checkHistory"; const val CHECK_BUG_TICKET = "checkBugTicket"
    /**
     * The post-deploy-check flow's own (bpmn/post-deploy-check.bpmn): when the check stops and asks the user
     * (`checkDeadlineAt`, a `java.util.Date` for `event-deadline`: the section's `Deadline:`, else the flow's start
     * plus 7 days), the user's answer there (`checkDeadlineAnswer`: `wait`, `drop` or `bug`), and whether a poll found
     * the merge deployed (`deployed`, a Boolean `task-poll-deployed` sets).
     */
    const val CHECK_DEADLINE_AT = "checkDeadlineAt"; const val CHECK_DEADLINE_ANSWER = "checkDeadlineAnswer"; const val DEPLOYED = "deployed"
    /** Operaton stores a String variable in a VARCHAR(4000); the tail stays under it. */
    const val TAIL_CHARS = 3900
}
