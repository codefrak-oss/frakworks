# Friction notes

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

A friction note (`engine.friction_note`; written by a Smith's `note_friction`,
"The frakworks server in a Smith", by "The retro", and by First mate's
`note_friction`, "The frakworks server") is grouped with the notes that say
the same thing (#1094). The same thing means one change
would have spared every note in the group: one `chmod +x`, one line in a
manual, one tool added. The same tool, file or area with a different cause is
two groups.

**The exact key, at insert.** Every note gets a `signature` in the same
`INSERT` that writes it, for every source: the SHA-256 of the field the key
line came from (`evidence`, or `summary` when the note has no evidence) and
that field's first non-blank line normalised by the doctor's
`Signature.normalise` (UUIDs, hex runs of 7 or more and digit runs become
`#`, whitespace collapsed, cut to 160 characters), so `… exit 126` and `…
exit 127` are one key. `kind` and `repo` are not in it: a kind is the
author's label, and a sandbox problem is the same friction in every target
repo. `FrictionKey` in `friction/FrictionKey.kt`; V39 backfilled it for
every row written before.

**The grouper.** `FrictionGrouper` (`friction/FrictionGrouper.kt`) is one
worker thread, `friction-grouper`, the only thing that groups a note. It is
poked, never run, by two callers: `FrictionNotes.insert`, after its
transaction commits, and a `@Scheduled` method every
`engine.friction.group-interval` (PT1M), the backstop for a note written just
before a restart or one whose retry has come round. A poke queues one drain
when none is queued (an `AtomicBoolean`); a drain takes the ungrouped notes
due now (`group_id IS NULL AND (next_attempt_at IS NULL OR next_attempt_at <=
now())`), oldest first, and for each, in one `REQUIRES_NEW` transaction of its
own so a failing note rolls back alone:

1. **Exact.** A key already in `engine.friction_signature` joins its group,
   `grouped_by: exact`, no Jev call. This is the whole of the work for a
   literal repeat.
2. **No groups yet.** A new key with no groups founds one, `grouped_by: new`,
   its summary's first line (cut to 240) as the title, its key as the group's
   first signature. No call.
3. **Jev, for a new key** (use case `friction-group`, `FrictionGroupCheck`):
   one `choice` whose state is the note's `kind`, `source`, `repo`, `summary`
   and `evidence` cut to 2,000 characters, and whose options are every group,
   newest member first, as `g<id>` with its title cut to 240, plus `new`.
   The answer is read over **clusters of near-duplicate groups**, not pick by
   pick (#1126: once one problem sat in several groups, Jev's probability
   split among them, no pick reached the old 0.8 floor and each note founded
   yet another group). Clusters are found in the engine, with no second
   question: two candidate groups are the same problem when the Jaccard
   similarity of their titles' words (lower-cased runs of letters, digits and
   `/._-`, a few stop words like "the", "is", "in" left out) is **0.5** or
   above (`FrictionGroupCheck.CLUSTER_SIMILARITY`), and a cluster is every
   group linked so, directly or through another; a group near no other is a
   cluster of one. A cluster's probability is the sum of its members'. The
   winning cluster **clearly beats `new`** when its sum is above `new`'s and
   at least `engine.jev.friction-group-beats-new` (2.0) times it; then the
   note joins the cluster's **oldest group** (lowest id: where the problem was
   first filed, and what a merge keeps), `grouped_by: jev`, `jev_call_id`
   set, and **adds the note's key to that group's signatures** in the same
   transaction, so the next note with that line joins by step 1. Otherwise
   (`new` on top, or a winner under twice `new`) the note founds a group as
   in step 2, with the call id. Past 254 groups the choice runs in the intake
   duplicate check's stages (`DuplicateCheck.stages`, generalised over the
   candidate): batches of 254 newest first, one call each, then one call over
   the batches' picks, read as above, its action prefixed `stage 2`; a
   stage-1 call with no answer ends the check as no answer.
4. **No answer** (Jev unreachable, over its limit, an unreadable body): the
   note waits. `group_attempts` goes up by one and `next_attempt_at` is set by
   a backoff of 1, 5, 15 and 60 minutes, then hourly; the scheduled poke
   retries it when due. After `engine.friction.group-give-up` (PT24H from
   `noted_at`) it founds its own group, `grouped_by: fallback`. With **no
   usable jev account** (the call row's `error`) it founds its own group at
   once, `grouped_by: no-jev`: an installation without Jev gets exact grouping.

The Jev call is made outside the note's transaction, so a group write that
fails after an answer leaves the call row (one duplicate in the audit on the
re-drain, marked by its action) and never a duplicate group. The engine runs
as one instance, so no row is locked; if that ever differs, the drain takes
`SELECT … FOR UPDATE SKIP LOCKED`. `engine.friction.group-enabled=false`
turns both pokes off (the tests' properties; a test drains by hand).

**The audit.** Every `friction-group` call row gets the beats-`new` ratio
(`engine.jev.friction-group-beats-new`, 2.0) as `threshold`, so `GET
/jev/summary`'s `floor` for this use case is that ratio, not a confidence;
and as `action` what was done, within 100 characters: `g12 (0.91 vs new
0.09): joined`, `g2 cluster{g2,g9,g27,g32} (0.73 vs new 0.11): joined` (the
joined group first, at most four members named, then `+N`), `g12 (0.64 vs
new 0.36): new group g31`, `new (0.88): new group g31`, `no
answer: left ungrouped` (`no answer: new group g31` when the note founded its
own as `fallback` or `no-jev`), and the stage-1 actions `stage 1 (i/n) g12
(0.60): to stage 2`, `… new (0.90): out`, `… no answer: left ungrouped`. Its
`outcome` is written by the corrections below and by the 30-day sweep (`joined
g12: kept`, `joined g12: pulled out to g40 by @x`, `new g31: kept`, `new g31:
merged into g12 by @x`), which `FrictionGroupCheck.truth` reads for `GET
/jev/summary` ("Jev decisions").

**Corrections** (#1098; the proposal's §6; `FrictionCorrections`). Any login
may correct a grouping from the desktop's Analytics > Friction notes; a Smith
sandbox, by its source address, is refused with 403 (a Smith does not regroup
what it reported), and 503 says the sandbox provider could not be asked. `by`
in the body is the login; no repo access is checked. There is no undo but the
inverse action.

- **Pull out**, `PATCH /friction/notes/{id}` with `{"group": null}`: the note leaves its group for a new one holding it alone,
  titled by its summary line, `grouped_by` `user`, `grouped_login` (V41, a
  plain SQL migration; null on every row before it) the bearer token's login; it keeps its
  call. Its key moves to the new group's signatures **unless another member of
  the old group shares it**, in which case the key stays (a key points one
  way) and a later note with that line joins the old group. A `jev` note's
  call gets `joined g12: pulled out to g40 by @x`. 400 on a body with `by` or a
  non-null `group` (a note goes out only to a new group), 404 for no such
  note, 409 for a note not grouped yet or the only member of its group
  (pulling it out changes nothing).
- **Merge**, `POST /friction/groups/{id}/merge` with `{"into": <id>}` (the ids are the BIGSERIAL numbers `GET /friction/groups`
  lists): every note and every signature of `id` moves into `into`, which
  keeps its title, and `id` is deleted; the moved notes keep their
  `grouped_by`, call and login. The merged group's founder call (its `new`
  member with a call) gets `new g31: merged into g12 by @x`, and every `jev`
  call of the receiving group with no outcome yet gets `joined g12: kept`.
  404 for either group missing, 409 for a merge into itself.
- **The sweep.** Every drain of the grouper ends with `FrictionGrouper.sweep`:
  a `friction-group` call 30 days old with no outcome whose note is still in
  the group the call put it in (the group its `action` names,
  `FrictionGroupCheck.placed`) gets `joined g12: kept` for a `jev` note and
  `new g31: kept` for a `new` one. A note moved since by a merge is left: its
  group is gone, and no later sweep writes on it.

An outcome is never overwritten, and only `friction-group` calls are ever
touched: a note whose `jev_call_id` names another use case's call (none does
today) gets no outcome.

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPATCH "$ENGINE_URL/friction/notes/<uuid>" -H 'content-type: application/json' -d '{"group":null}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPOST "$ENGINE_URL/friction/groups/31/merge" -H 'content-type: application/json' -d '{"into":12}'
```

**Fixed by** (#1200; `FrictionFixes`, V44). A group has at most one ticket
that fixes it, in `engine.friction_group`'s `fix_repo`, `fix_issue`,
`fix_state`, `fix_merged_at`, `fix_recurred_at` and `fix_dismissed`. The link
is set three ways:

- **When the engine files a ticket** whose body names `friction group g<id>`
  (or `friction groups g6, g9 and g12`; `IntakeDelegates`), each named group
  with no link yet is linked as `fix-filed`.
- **By the sync**, every `engine.doctor.bug-sync` (`FrictionFixTick`, off with
  `engine.friction.group-enabled=false`): GitHub's search over each target
  repo's issues whose body names `friction group` links the groups they name
  that have no link (the lowest number wins; a later ticket is logged), and
  brings linked ones in step; a link to a ticket that names no group follows
  the repo's newest 100 closed issues, and one not among them (an older
  ticket, or a pull request) is read directly by number on every pass
  (`IssueDetails.issueOrPull`, one GraphQL query per link, whatever its age;
  #1657): an issue closed as completed is `fixed` at its merged closing PR's
  merge time, else at its `closedAt`; a merged PR is `fixed` at `mergedAt`; an
  issue closed as not planned (or a duplicate) or a PR closed unmerged clears
  the link (the group is `open`). Each change is in the answer's `changed`; a
  number GitHub has no issue or PR for is under `skipped`. The newest-100 scan
  only discovers; the direct read only follows links already set. Its first
  pass after deploy is the backfill.
- **By a user** (`PATCH /friction/groups/{id}`, the desktop's Fixed by field)
  **or First mate** (`link_friction_fix({group, repo, issue})`, `issue: null`
  to clear). A cleared ticket is remembered in `fix_dismissed`, so the sync
  does not link it again. Clearing also drops a pending intake.
- **By intake id** (#1337, V53: `fix_intake`, `fix_intake_repo`). A Smith or
  First mate that notes friction and files its fix as an ask in the same
  session links the group (`link_friction_fix_intake({intake, note | group})`;
  a user `POST /friction/groups/{id}/fix-intake` or
  `POST /friction/notes/{id}/fix-intake`) to the intake id `submit_ask`
  answered. While the intake has filed no ticket the group is `fix-pending`
  (never `fix-filed`), its `fix` `{intake, repo, issue: null}`. When the
  intake files (either filing delegate, labelled or unlabeled triage, calls
  `FrictionFixes.intakeFiled`), or at the latest at the next sync pass (the
  intake's historic `ticket` variable), the link becomes a ticket link,
  `fix-filed`, and follows the ticket as below. An intake that **ends without
  a `ticket` variable** (cancelled, closed, answered, a duplicate, refused,
  insufficient-permissions: every outcome but filing) clears the pending link
  at the next sync, reported under `changed` with `issue` null, `from`
  `fix-pending` and `to` null, and the group is `open` again; so does an id
  the history no longer knows. A **group already linked to a ticket is
  refused** (409; the tool an error): the pending link never overwrites it,
  clear it first. An intake that already filed links its ticket at once; one
  that already ended without a ticket is refused. A ticket the sync finds
  naming a pending group links it as usual and drops the pending intake.

The state follows the ticket: open is `fix-filed`; closed as completed with a
merged closing PR is `fixed` at the (latest) merge time; closed as not planned
or a duplicate clears the link; closed as completed with no merged PR stays
`fix-filed` and is logged. A note that joins a `fixed` group after the merge
marks it `recurred` (`fix_recurred_at`, the first such note). Analytics >
Friction notes hides `fixed` groups behind "Show fixed (n)" and highlights
`recurred` ones, and shows a pending group's fix as "Fix pending: intake
<short id>"; `read_friction_notes` rows carry `groupState` and `groupFix`.

**On demand** (#1207). `POST /friction/fixes/sync` (no body, no parameters)
and the diagnostics server's `sync_friction_fixes` run one pass of the sync
now (`FrictionFixes.syncNow`), say after a deploy or once a fixing ticket
closes, and answer what it did: `linked` (`{groupId, repo, issue, state}`),
`changed` (`{groupId, repo, issue, from, to, mergedAt}`; `to` null when the
link was cleared, `mergedAt` set when `fixed`), `recurred` (always empty: a
recurrence is marked when a note joins, `FrictionGroupFixes.recur`, never by the
sync) and `skipped` (`{groupId, repo, issue, reason}`: a ticket naming a group
with no row, a group already fixed by another ticket, a ticket closed as
completed with no merged PR, a repo whose issues could not be searched or
whose closed issues could not be read; `groupId` and `issue` null for a repo).
A GitHub failure is in `skipped`, still 200; an exception the pass does not
catch is a 500 (the tool: an error result). One pass runs at a time: asked
while one runs (on demand or the scheduled tick), the endpoint answers **409**
and the tool an error result at once, nothing waits; a tick that finds one
running skips. Refused to a Smith sandbox (403, the tool an error) and 503
when the sandbox provider cannot be asked, as the other corrections.

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPOST "$ENGINE_URL/friction/fixes/sync" | jq '{linked, changed, skipped}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPATCH "$ENGINE_URL/friction/groups/6" -H 'content-type: application/json' -d '{"fix":{"repo":"codefrak-oss/frakworks","issue":1200}}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPOST "$ENGINE_URL/friction/groups/6/fix-intake" -H 'content-type: application/json' -d '{"intake":"<intake id>"}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" "$ENGINE_URL/friction/groups?state=unsolved" | jq '.[] | {id, state, fix}'
```

**Two things** (#1112; the proposal's §7; `FrictionBugs`). The call whose pick
counts also carries a second question, `two-things`, a `noul`: "Does this note
describe two different things, each of which a different change would spare?
One thing with two symptoms, or one thing and its workaround, is no." A note
that makes no group call (an exact join, the first founder) gets the `noul`
alone, use case `friction-two-things`, same state and question. At
`engine.jev.friction-two-things` (0.8) or above the note's `two_things` is the
value and `two_things_call_id` the call, written in the same transaction as
its grouping, and the call's `action` gains `; two things (0.86)` (a lone
call's is `marked; two things (0.86)` or `no mark`); below it nothing but the
call is written; no answer is no mark and is not retried. The note stays in
the group its key line put it in: it is honoured as one thing.

The first mark on a group with no bug (`bug_state` null), in a repo whose
**`frictionBugs`** switch is on (`PATCH /repos/{owner}/{name}` `{"frictionBugs":
true}`, off for every repo, independent of `doctorBugs` and `engineDefects`,
403 from a Smith sandbox; the desktop's Config tile has the checkbox), claims
the group's bug as `filing` in the mark's transaction and, once it has
committed, starts an `intake` in **the note's repo** as the doctor files a bug:
`submittedBy` `friction`, authorized as the engine's `friction` actor
(`authorizedByEngine`, so the duplicate check is skipped and the refined bug's
flow starts with no trusted login), `Kind: bug`, a `frictionBug` variable naming the
group. The ask carries the note's id, summary and evidence cut to 4,000, the
group's title, the ticket and run it came from, the question why this run
wrote two things in one note and what in its instructions led it to, and
cites `bots/smith.md` "Friction notes": one thing per note; a second thing is
a second note. A start that fails unclaims. `FileTicketDelegate` and
`FileUnlabeledTicketDelegate` write the refined ticket's number and `open` on
the group. A note whose repo is null (First mate's with none) files nothing.
With the switch off the mark alone is written and `bug_state` stays null, so
turning it on later lets the next mark file; nothing is filed retroactively.
A later mark on a group with an open bug comments on the bug (the note's id,
ticket, run and summary line). Every `engine.doctor.bug-sync` (`FrictionBugTick`)
a bug the engine sees closed on GitHub becomes `closed`, and the next mark
files afresh; a `filing` bug whose intake ended without filing (cancelled
before it filed) is unclaimed. A merge keeps the receiving group's bug; the
merged group's bug columns go with its row. **Not two things**, `PATCH
/friction/notes/{id}` with `{"twoThings": null}`, clears the mark, files
and closes nothing, and writes `two things: cleared by @x` on the mark's call;
the 30-day sweep writes `two things: kept` on a lone `friction-two-things` call
whose mark still stands (a group call's `kept` gains nothing). `GET
/jev/summary` lists `friction-two-things` and judges both outcomes
(`JevAgreement`: `kept` agrees with a yes, `cleared` with a no).

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPATCH "$ENGINE_URL/friction/notes/<uuid>" -H 'content-type: application/json' -d '{"twoThings":null}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPATCH "$ENGINE_URL/repos/<owner>/<name>" -H 'content-type: application/json' -d '{"frictionBugs":true}'
```

**Reading it.** `GET /friction/notes` and `read_friction_notes` serve each
note's `signature`, `group_id`, `grouped_by`, `jev_call_id`, `grouped_login`
(the login that pulled it out, on a `user` row) and `jev_confidence` (the
confidence of its `friction-group` call's `group` answer, read from
`jev_call.response` through `JevAnswers.parse` once per distinct call in a
listing, never once per row; null without a call or an answer:
`FrictionConfidence`), `two_things` and `two_things_call_id`; `GET
/friction/groups?since&repo&kind&source&limit` answers the groups newest
passing member first, each with `count` (members passing the filters),
`total`, `newest`, `oldest`, `kind` (the majority, `mixed` on a tie), `repos`,
`severity` (the highest), `bug` (`{repo, issue, state}`, null without one) and `members` as `/friction/notes` rows, a group
shown when at least one member passes, `limit` counting groups (100, at most
500); `GET /friction/summary` gains `groups` and `ungrouped` beside totals that
still count raw notes. The tables are `engine.friction_group` and
`engine.friction_signature` ("Its own tables").

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" "$ENGINE_URL/friction/groups?since=2026-10-01&limit=5" | jq '.[] | {id, title, count, total, kind}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" "$ENGINE_URL/friction/summary" | jq '{total, groups, ungrouped}'
```
