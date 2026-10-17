# The Doctor's non-success runs, 2026-09-21 to 2026-09-28

Second pass over the 12 tickets the Doctor worked on in the Analytics > Doctor
window, against the known process-instance migration issue (#776). Data read
on 2026-09-28 at about 17:25Z from `GET /doctor/runs` (window
`since=2026-09-21T17:23:05Z`, the default 7 days: 50 runs, 12 tickets, all in
`codefrak-oss/frakworks`), `GET /camunda/instances?issueUrl=…`,
`GET /camunda/instances/{id}` (the ticket flows and the doctor runs' own
`doctor` / `smith-run` instances), `GET /camunda/definitions/{id}/xml`, the
engine code on `main` at f6a745d, and GitHub issues (read only).

## Summary

| Verdict | Tickets | Which |
|---|---|---|
| Explained by the migration issue | 1 | #758 |
| Not explained by the migration issue | 11 | #525, #541, #549, #591, #610, #629, #696, #697, #750, #751, #770 |
| Undetermined | 0 | |

- The migration issue (tracked as **#773**) explains exactly one ticket,
  #758: its root is on `feature:19`, whose `gateway-work-outcome` has no
  branch for the `out-of-scope` outcome the engine has emitted since #761.
  The evidence shows one issue, described correctly; nothing here reframes it.
- The other eleven have five causes, none of them a stale definition: the
  intake's unbuilt `task-file-unlabeled` step (4 tickets), a refiner drafting
  `.github/workflows/` into an Allowed scope (3), a push of a workflow file
  refused for want of the `workflow` token scope (2), a replan rule that keeps
  refusing #751's replan (1), and a transient TLS error cloning the repo (1).
- Four of the 12 are successes, not failures, by the desktop's own rule and in
  fact: #591, #629, #750 and #770 were moved on by an applied `retry` and their
  flows ended. #751's one applied retry is scored a success too, but it was not
  (bug 2).
- All 20 `failed` doctor runs (on #750 and #751) are the doctor's own Smith
  failing on the network or the inference gateway before it could diagnose
  anything.
- 4 bugs found and filed as asks (see Bugs). The largest cost is bug 1: the
  doctor ran 23 times on #751's one incident and 11 times on #750's.
- Now (17:25Z) only #751 and #758 are still stuck; the other ten flows ended.

<svg xmlns="http://www.w3.org/2000/svg" width="720" height="392" viewBox="0 0 720 392" font-family="sans-serif" font-size="12" role="img" aria-label="Doctor runs per ticket by outcome"><rect width="720" height="392" fill="#ffffff"/><text x="70" y="20" font-size="13" font-weight="bold" fill="#222">Doctor runs per ticket, by outcome (GET /doctor/runs, 7-day window)</text><text x="62" y="49" text-anchor="end" fill="#222">#751</text><rect x="70" y="36" width="23" height="18" fill="#2a9d8f"><title>#751: 1 applied</title></rect><text x="82.0" y="49" text-anchor="middle" fill="#fff" font-size="11">1</text><rect x="94" y="36" width="263" height="18" fill="#8d99ae"><title>#751: 11 none</title></rect><text x="226.0" y="49" text-anchor="middle" fill="#fff" font-size="11">11</text><rect x="358" y="36" width="287" height="18" fill="#d1495b"><title>#751: 12 failed</title></rect><text x="502.0" y="49" text-anchor="middle" fill="#fff" font-size="11">12</text><text x="652" y="49" fill="#555">24</text><text x="62" y="75" text-anchor="end" fill="#222">#750</text><rect x="70" y="62" width="23" height="18" fill="#2a9d8f"><title>#750: 1 applied</title></rect><text x="82.0" y="75" text-anchor="middle" fill="#fff" font-size="11">1</text><rect x="94" y="62" width="71" height="18" fill="#8d99ae"><title>#750: 3 none</title></rect><text x="130.0" y="75" text-anchor="middle" fill="#fff" font-size="11">3</text><rect x="166" y="62" width="191" height="18" fill="#d1495b"><title>#750: 8 failed</title></rect><text x="262.0" y="75" text-anchor="middle" fill="#fff" font-size="11">8</text><text x="364" y="75" fill="#555">12</text><text x="62" y="101" text-anchor="end" fill="#222">#758 *</text><rect x="70" y="88" width="71" height="18" fill="#8d99ae"><title>#758: 3 none</title></rect><text x="106.0" y="101" text-anchor="middle" fill="#fff" font-size="11">3</text><text x="148" y="101" fill="#555">3</text><text x="62" y="127" text-anchor="end" fill="#222">#610</text><rect x="70" y="114" width="47" height="18" fill="#8d99ae"><title>#610: 2 none</title></rect><text x="94.0" y="127" text-anchor="middle" fill="#fff" font-size="11">2</text><text x="124" y="127" fill="#555">2</text><text x="62" y="153" text-anchor="end" fill="#222">#541</text><rect x="70" y="140" width="47" height="18" fill="#8d99ae"><title>#541: 2 none</title></rect><text x="94.0" y="153" text-anchor="middle" fill="#fff" font-size="11">2</text><text x="124" y="153" fill="#555">2</text><text x="62" y="179" text-anchor="end" fill="#222">#770</text><rect x="70" y="166" width="23" height="18" fill="#2a9d8f"><title>#770: 1 applied</title></rect><text x="82.0" y="179" text-anchor="middle" fill="#fff" font-size="11">1</text><text x="100" y="179" fill="#555">1</text><text x="62" y="205" text-anchor="end" fill="#222">#697</text><rect x="70" y="192" width="23" height="18" fill="#8d99ae"><title>#697: 1 none</title></rect><text x="82.0" y="205" text-anchor="middle" fill="#fff" font-size="11">1</text><text x="100" y="205" fill="#555">1</text><text x="62" y="231" text-anchor="end" fill="#222">#696</text><rect x="70" y="218" width="23" height="18" fill="#8d99ae"><title>#696: 1 none</title></rect><text x="82.0" y="231" text-anchor="middle" fill="#fff" font-size="11">1</text><text x="100" y="231" fill="#555">1</text><text x="62" y="257" text-anchor="end" fill="#222">#629</text><rect x="70" y="244" width="23" height="18" fill="#2a9d8f"><title>#629: 1 applied</title></rect><text x="82.0" y="257" text-anchor="middle" fill="#fff" font-size="11">1</text><text x="100" y="257" fill="#555">1</text><text x="62" y="283" text-anchor="end" fill="#222">#591</text><rect x="70" y="270" width="23" height="18" fill="#2a9d8f"><title>#591: 1 applied</title></rect><text x="82.0" y="283" text-anchor="middle" fill="#fff" font-size="11">1</text><text x="100" y="283" fill="#555">1</text><text x="62" y="309" text-anchor="end" fill="#222">#549</text><rect x="70" y="296" width="23" height="18" fill="#8d99ae"><title>#549: 1 none</title></rect><text x="82.0" y="309" text-anchor="middle" fill="#fff" font-size="11">1</text><text x="100" y="309" fill="#555">1</text><text x="62" y="335" text-anchor="end" fill="#222">#525</text><rect x="70" y="322" width="23" height="18" fill="#8d99ae"><title>#525: 1 none</title></rect><text x="82.0" y="335" text-anchor="middle" fill="#fff" font-size="11">1</text><text x="100" y="335" fill="#555">1</text><rect x="70" y="360" width="12" height="12" fill="#2a9d8f"/><text x="87" y="370" fill="#222">applied (remedy applied)</text><rect x="270" y="360" width="12" height="12" fill="#8d99ae"/><text x="287" y="370" fill="#222">none (healthy / unfixable)</text><rect x="470" y="360" width="12" height="12" fill="#d1495b"/><text x="487" y="370" fill="#222">failed (doctor run itself failed)</text><text x="70" y="388" fill="#555" font-size="11">* #758 is the one ticket the migration issue explains.</text></svg>


## The tickets

Positions are shown without their `incident:<id>` entry; every run in the
window had exactly one open incident in its position. "Def." is the root's
process definition; "deployed then" is the newest version of that key when
the flow got stuck (deployments read from the definition ids' UUID times:
`ba07` 2026-09-27 00:10Z, `ba18` 02:09Z, `ba73` 12:58Z, `bb4b` 2026-09-28
14:46Z).

| Ticket | Runs | Outcomes (oldest first) | Stuck at | Def. / deployed then | Verdict | Evidence |
|---|---|---|---|---|---|---|
| codefrak-oss/frakworks#525 | 1 | none | `chore:task-open-pr@none` | `chore:18` / `chore:18` or the 00:10Z redeploy | Not explained | `OpenPrDelegate` got `PushRejected`: the bundle changes `.github/workflows/images.yml` and the fleet token lacks `workflow`. A GitHub refusal, not a BPMN routing gap; the doctor judged it `unfixable`. |
| codefrak-oss/frakworks#541 | 2 | none, none | `feature:task-open-pr@none` | `feature:15` / `feature:15` | Not explained | Same as #525 (images.yml, no `workflow` scope), on two successive incidents (a1e6a60d, then aa688497). The flow ran on the definition deployed three minutes before it started. |
| codefrak-oss/frakworks#549 | 1 | none | `intake:task-file-unlabeled@none` | `intake:22` / `intake:22` | Not explained | Refiner returned `capability`; `task-file-unlabeled` is `${notBuilt}`, which always throws (`UnsupportedOperationException: task-file-unlabeled is not built yet`). Still `${notBuilt}` on `main`; building it is #730. |
| codefrak-oss/frakworks#591 | 1 | applied (retry) | `smith-run:task-remove-sandbox@none` | `intake:24` / `intake:24` | Not explained | Refine result refused by `RefineResult.problems`: a `file` whose Allowed scope reaches `.github/workflows/` without the `workflow` scope. The retry reran the refiner and the intake ended (03:26Z): a success. |
| codefrak-oss/frakworks#610 | 2 | none, none | `intake:task-file-unlabeled@none` | `intake:24` / `intake:24` | Not explained | `capability` outcome into the unbuilt `task-file-unlabeled`, as #549. Two runs on the one incident d700f1a8 (04:01Z, 11:24Z): a re-diagnosis (bug 1). |
| codefrak-oss/frakworks#629 | 1 | applied (retry) | `smith-run:task-remove-sandbox@none` | `intake:24` / `intake:24` | Not explained | Same refusal as #591 (`.github/workflows/` in a refined ticket's scope). The doctor chose `retry`, the refiner reran and the intake ended (11:49Z): a success. |
| codefrak-oss/frakworks#696 | 1 | none | `intake:task-file-unlabeled@none` | `intake:26` / `intake:26` | Not explained | `capability` (the ask writes under `.claude/**`) into the unbuilt `task-file-unlabeled`. |
| codefrak-oss/frakworks#697 | 1 | none | `intake:task-file-unlabeled@none` | `intake:26` / `intake:26` | Not explained | As #696 (`.claude/skills/intake/SKILL.md`). |
| codefrak-oss/frakworks#750 | 12 | none, none, failed ×8, none, applied (retry) | `smith-run:task-remove-sandbox@none` | `intake:26` / `intake:26` | Not explained | Same refusal as #591/#629. Stuck 04:24Z; the first three diagnoses said a retry would replay the same stored result and judged it `unfixable`, the twelfth (14:38Z) saw that `IncidentController` reruns the Smith for this message, retried, and the intake ended at 14:49Z: a success, ten hours late. |
| codefrak-oss/frakworks#751 | 24 | applied (retry), none ×3, failed ×9, none ×5, failed ×3, none ×3 | `smith-run:task-remove-sandbox@none` | `feature:19` / `feature:19` (stale only since 14:46Z) | Not explained | Replan result refused by `ChildDraft.replanProblems`: `children[1] records a filed child's merge ("merged in PR #761")`. The 04:50Z retry reran the replan Smith, which broke the same rule a minute later (incident 043899f6 → 3670493a). An engine code rule, independent of the definition version; still open (bug 3). |
| codefrak-oss/frakworks#758 | 3 | none, none, none | `smith-run:task-remove-sandbox@none` | `feature:19` / `feature:20` (the `bb4b` deploy) | **Explained** | The work run (on `reusable-smith-run:27`) ended `out-of-scope`; the root's `feature:19` XML has `flow-outcome-ask`, `-done`, `-nothing-to-do`, `-overflowed` and no `flow-outcome-out-of-scope`, which `main`'s `feature.bpmn` has. The gateway throws `ENGINE-02004 No outgoing sequence flow for … 'gateway-work-outcome'`. All three diagnoses say so. Still open. |
| codefrak-oss/frakworks#770 | 1 | applied (retry) | `smith-run:task-remove-sandbox@none` | `chore:27` / `chore:27` | Not explained | The review Smith's clone failed: `GnuTLS recv error (-110)`. Transient; the retry reran the review and the chore ended (16:42Z): a success. |

Run counts by outcome across the window: 5 `applied`, 25 `none`, 20
`failed`, 0 `running`.

## Patterns

1. **Capability intakes park at the unbuilt `task-file-unlabeled`**: #549,
   #610, #696, #697. Designed behaviour (`intake.bpmn:72`, "Not built"), not a
   bug; every `capability` outcome ends here until #730 is built. The doctor
   correctly calls each `unfixable`, so each costs one or more Smith runs for no
   remedy. Once #730 is built these stop reaching the doctor at all.
2. **A refiner drafts `.github/workflows/` into an Allowed scope the ask does
   not need**: #591, #629, #750. The engine's rule (`RefineResult.problems`,
   #492) refuses it as designed, and a `retry` reruns the refiner; each of the
   three intakes ended after one. Not an engine bug; a refiner habit. Within it,
   the doctor's judgement varied: #591 and #629 were retried at once, #750 was
   judged `unfixable` three times on the wrong premise that a retry replays the
   stored result (`IncidentController` restarts the call activity for "the
   smith's result breaks the rules"), costing ten hours.
3. **A work bundle touching `.github/workflows/` is refused at push**: #525,
   #541. Both on 2026-09-27, before the work scope fence (#753, PR #761); the
   engine now refuses a `.github/workflows/` path before pushing and returns it
   to the work run as a finding, so this failure mode should not recur as an
   incident.
4. **The same incident re-diagnosed again and again**: #610 (2 runs on one
   incident), #750 (11), #751 (23 on incident 3670493a), #758 (3). Each run
   has a fresh `stuckSince`; the episode resets whenever the flow's
   `doctor_position` row is more than two intervals old. #610's second run
   (2026-09-27 11:24Z) predates #757, so the churn is not #757's doing; #757
   only makes each reset start a run on the tick that sees it instead of one
   tick later. Runs on #750 and #751 start in the same second (12:36:12,
   13:03:13, 13:05:28, …), so the resets hit all stuck flows at once; whether
   from engine restarts or long ticks cannot be told from the sandbox. Bug 1.
5. **The doctor's own Smith fails on the network**: all 20 `failed` runs, on
   #750 (8) and #751 (12), between 12:36Z and 15:52Z on 2026-09-28. From the
   doctor instances' `smith-run` log tails: 15 × `Could not resolve host:
   github.com` (exit 128), 2 × `fatal: early EOF`, 1 × exit 128 with no message
   after 17 minutes (a clone that hung), 2 × `API Error: 502` from the inference
   gateway (exit 1). #770's own incident (a TLS error on clone) is the same host
   condition hitting a ticket's run. Infrastructure, not a code defect; not
   filed.
6. **Result-rule refusals show up as `smith-run:task-remove-sandbox@none`**:
   #591, #629, #750, #751, #758, #770. The failing code is `ReadResultDelegate`
   at `reusable-smith-run:task-read-result`, but it runs in the
   `task-remove-sandbox` job's transaction, so the incident and position name
   that activity. Two diagnoses on #750 spend paragraphs untangling it.
   Misleading, but correct Operaton behaviour; noted, not filed.
7. **Before versus after #757** (merged 2026-09-28 04:36Z): 11 runs predate it
   (every run on #525, #541, #549, #591, #610, #629, #696, #697, and #750's
   first, each with `stuckSince` one tick before it started), 39 follow it
   (`stuckSince` equal to the start). The three tickets with many runs (#750,
   #751, #758) are all after it; see pattern 4 for why that is the reset, not
   #757.

## Bugs

The migration issue itself is **#773** (open): "Engine: migrate running
process instances to the newest BPMN definition at startup". It already names
#758 as its example and needs nothing from this report. #751 is also on
`feature:19` and will hit the same gap if a later work run of it is fenced.

Each bug below was filed as its own ask (`submit_ask`, discovered work per
`smith.md`); an ask becomes a numbered ticket once it is labelled
`authorized` and refined, so the intake id is given.

1. **The doctor re-diagnoses an unchanged incident on every stuck-episode
   reset.** Symptom: 23 doctor runs on #751's single incident 3670493a, 11 on
   #750's 5ff738e3, 3 on #758's 83c04270, 2 on #610's d700f1a8, each with a new
   `stuckSince` and the same diagnosis. Evidence: `Doctor.tick`
   (`engine/.../doctor/Doctor.kt:64-77`) treats a `doctor_position` row older
   than two intervals as a baseline and sets `since` to now, and
   `runs.ranFor(root, position, since)` keys the episode on that `since`, so
   the same position becomes a new episode. Filed: intake
   `e6f78947…`. (The ask says 22 runs, 9 `unfixable`
   on 3670493a; the count is 23, 11 `none`.)
2. **Analytics > Doctor scores a retry that reproduced the failure as a
   success, and calls a failed doctor run "the remedy failed".** Symptom: #751's
   run 1458b6d9 (`retry` of 043899f6, 04:50Z) is "succeeded" though the flow
   was stuck again at the same leaf a minute later, with the same message, for
   twelve hours. Evidence: `classifyDoctorRuns`
   (`observability/desktop/src/renderer/doctor.jsx:38-55`) compares the whole
   position string, which includes `incident:<id>`, and a rerun Smith that
   breaks the same rule opens a new incident id. And all 20 `failed` runs failed
   before any remedy (pattern 5). Filed: intake
   `eab92548…`.
3. **The replan rule refuses a draft that names its merged dependency, and
   replan Smiths keep writing it.** Symptom: #751 has been stuck since 04:50Z.
   Evidence: `ChildDraft.replanProblems`
   (`engine/.../feature/FeatureResults.kt:139,161-162`) refuses any
   `merged in PR #<filed child's PR>` in a remaining draft; part 2 of #751
   depends on part 1 (#753, PR #761) and two fresh replan runs both said so,
   although the replan prompt (`engine/smith/run-smith.sh:328`) bans the phrase.
   Filed: intake `ee3b4749…`.
4. **The work scope fence reads a bare extension token as a root path.**
   Symptom: #758's work run was fenced `out-of-scope` (and then hit the
   migration gap). Evidence: `AllowedScope.of`
   (`engine/.../smith/AllowedScope.kt:43-54`) takes `` `.html` `` in
   ``- `docs/proposals/first-mate-compose.md` and `.html` `` as an entry, and
   `.html` matches only a root file of that name, not
   `docs/proposals/first-mate-compose.html`, which the plan edits. This is the
   doctor's diagnosis, matched against the code; the refused path list itself is
   not in the flow's variables I could read. Filed: intake
   `f1a7222a…`.

Not bugs: the unbuilt `task-file-unlabeled` (designed, #730), the refiner
over-scoping (the engine's rule caught it and a retry recovered it), the
sandbox network failures (host), and the incident's activity attribution
(Operaton transaction boundaries).
