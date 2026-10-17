---
entity: doc/bots
---

# Autonomous Bot Work Queue

> Status (2026-09-07): **the protocol below is live.** The queue labels exist on
> the repo, the runner is [`dispatch/dowork.sh`](../dispatch/), and the Smith
> instruction file is [smith.md](smith.md). Claiming became an atomic git ref in
> [#28](https://github.com/codefrak-oss/frakworks/issues/28), which retired
> the single-dispatcher "Architect" in favour of a per-host **Operator** loop.
> Full rationale for the decisions below is in MDR thoughts #854 (the codename +
> backend decision), #853 / [delegation.md](delegation.md) (the authority model)
> and #876 (the claim lock). The runner that started Smiths until 2026-09-24 is
> the Operator; its manual `operator.md` was removed 2026-09-27 (git history has
> it). How Smiths run now: [`engine/README.md`](../engine/README.md).

## What this is

A system to record work for autonomous bots to do, and for autonomous bots to
file new work they discover. The queue is **GitHub Issues on this repo**
(`github.com/codefrak-oss/frakworks`). MDR stays the reasoning log — it has
no supersession, so it is the wrong shape for "what should run next"; a bot
working an issue captures narrative thoughts to MDR while the issue holds the
state.

Why Issues and not Trello / a Jira-like: every Claude session already has `gh`,
no new credentials, real concurrency handling via the API, the queue lives in
the same repo as the code, and bots file new work the same way they consume it.
Trello was considered and deferred — the REST API has no atomic claim, Butler
automation is capped at 250 runs/month, and state would live only in Trello
(not in git, the graph can't ingest it). A one-way Issues→Trello mirror is a
documented fallback if an iPad glance view is wanted; the GitHub mobile app's
Projects board is being tried first.

## Smith run modes

The engine starts every Smith in one mode, which picks its prompt and its
result's shape (`engine/smith/run-smith.sh`, `smith/schemas`): `refine` (an
intake), `design`, `work`, `reproduce` (a bug), `review`, `security`,
`verify`, `accept`, `replan` (a split feature's step after each child),
`parent`, the long-running parent Smith of a split feature under `childMode`
`live`, which watches and steers the children while they are worked
([smith.md](smith.md), "Parent mode"; `engine/README.md`, "Split features",
"Live parent"), and `check`, the post-deployment check a merged PR's
`## POST-DEPLOYMENT-CHECK` section asks for, answered `passed`, `failed` or
`not_yet` ([smith.md](smith.md), "The post-deployment check" for the work
Smith that writes the section and "The check Smith" for the one that answers
it; `engine/docs/smith-runs.md`, "The check mode").

## Codename glossary (Matrix / Agent Smith)

| Term | Means |
|---|---|
| **Agent Smith** / a **Smith** | A worker bot — one Claude session executing one issue. Plural "Agents Smith". |
| **the Operator** | The runner loop — `dispatch/dowork.sh`. **One per host, and every host runs its own.** It reconciles, wins claim locks, spawns Smiths and reaps dead ones. Named for the crew member who runs the board on a ship in the Matrix: there is one on every ship, and none of them is in charge of the others. |
| **the claim lock** | `refs/claims/issue-<N>` on this repo. Creating it is atomic, so it — not the assignee, not the `claimed` label — is what it means to own an issue. |
| **the lease** | The heartbeat on a claim lock. When it goes stale, any Operator on any host may reap the lock. **Only issue claims are leases** — see §"Two kinds of ref live in `refs/claims/*`". |
| **a filing lock** | `refs/claims/review-*` / `refs/claims/secreview-*` / `refs/claims/rebase-*` / `refs/claims/merge-*`. Not ownership and not a lease: a permanent record that a ticket was already filed — or a merge decision already taken — for one PR revision, so two hosts cannot do it twice and a closed ticket cannot cause a re-file. |
| **the Source** | Central config: where Smiths get instructions, and where they are un-made. |
| **returning to the Source** | Clean shutdown — a Smith's stop predicate trips (issue closed, `bot-task` gone, or the lock no longer names it), it says so and exits. Never `kill -9` a Smith mid-task; that is the *Fantasia broom* mistake — a violent, uncoordinated stop that leaves partial state or respawns. |
| **an anomaly** | A stuck, looping, or runaway Smith — a process that should have been deleted and wasn't. Note that the lease does **not** catch one: a looping Smith is alive, so its heartbeat keeps renewing. |
| **flushed to Zion** | Reaping a dead Smith's session and workdir. |
| **the Oracle** | The one fixed-point host in the fleet: a dedicated VM (not a Smith VM) that holds every singleton — the GitHub webhook receiver behind Tailscale Funnel, the MCP server, and whatever the text channel becomes. Smiths are replaceable; the Oracle is the place everyone goes for answers and the only door the outside world may knock on. A dedicated VM in the deployment (its address is a deployment value), **built 2026-09-12**; its first tenant, the webhook receiver, went live the same day. The receiver now lives in the engine (`POST /hooks/github`, since 2026-10-02; [`engine/README.md`](../engine/README.md), "When it scans"). See [`servers/oracle.md`](../servers/oracle.md). |
| ~~**the Architect**~~ | *Retired 2026-09-07 (#28).* The single dispatcher that assigned issues to Smiths, back when claiming needed one process to be the only assigner. The claim lock removed the need for a singleton; the Operator replaced it. The name survives only in history from before the proposals folder under `bots/` was removed from this repo in the 2026-09-22 move. |

The joke is deliberate: the fleet is named after the antagonist whose defining
trait is replicating out of control, as a standing reminder in the logs of what
the spawn caps are guarding against.

## Label protocol

One board, driven entirely by labels and assignee. These were created on the
repo on 2026-09-06; the commands are kept as the definition of the board:

```bash
gh label create bot-task   --color 5319e7 --description "Approved for autonomous execution"
gh label create p0          --color b60205 --description "Drop everything"
gh label create p1          --color d93f0b --description "Ahead of everything queued"
gh label create p2          --color fbca04 --description "Normal work"
gh label create p3          --color c2e0c6 --description "Worthwhile, nobody waiting"
gh label create p4          --color bfdadc --description "When idle"
gh label create p5          --color e6e6e6 --description "Backlog, whenever"
gh label create claimed     --color 0e8a16 --description "A Smith has picked this up"
gh label create blocked     --color d4c5f9 --description "Waiting on a dependency or a human"
gh label create needs-review --color 1d76db --description "Work done, awaiting human sign-off"
gh label create intake      --color f9d0c4 --description "A raw ask for a Smith to refine into a ticket"   # 2026-09-16, #890
```

| Issue state | Meaning | Who sets it |
|---|---|---|
| Open, **no** `bot-task` | Filed by hand with `gh` and **not yet released.** Human triage inbox. Since 2026-09-15 the ticket MCP never produces this state: `file_bot_task` releases on filing. | a human using `gh` |
| Open, `bot-task`, **no claim lock** | **Queued** — unless something still open is blocking it (see below). Available for any Operator to claim. | the filer: `file_bot_task` on the ticket MCP applies `bot-task` + a required priority for every role; a Smith filing discovered work stays within `smith.md` §"Discovered work" (scope can only shrink, never `p0`); a hand-filed ticket is released by a human adding the labels |
| Open, `bot-task`, **claim lock held** | **In progress.** The Smith named in the lock owns it. | the Operator, atomically, on claim |
| Open, `bot-task`, no lock, **future `Not before:`** | **Scheduled** — queued but not yet eligible; the Operator skips it each tick and says so in the run log. The state is driven by the `- Not before: <T>` line in the body and the clock, and it is what `smith_queue_open{state="scheduled"}` counts. It also carries a `scheduled` label, applied and cleared by the fleet reconciler's label pass, which is **cosmetic in the sense of §"`claimed` and the assignee are cosmetic" below** — it is there so a waiting ticket is recognisable on the board and on the phone, and nothing in the protocol reads it. A **malformed** `Not before:` is not this state — the claim gate labels it `blocked` and says why. | nobody: the body plus the clock |
| Open, `blocked` | Stalled. A comment states why and what unblocks it. | the Smith, or a reap / lease sweep |
| Open, `needs-review` | Work complete, PR open, awaiting a human. The PR body says `Closes #<n>` so the merge closes the issue. | the Smith |
| Closed | Done (or won't-do — the closing comment says which). | the Smith or a human |

Priority is `p0`..`p5`, ordered highest first: `p0` drop everything (human-only, at a keyboard), `p1` ahead of everything queued, `p2` normal work, `p3` worthwhile with nobody waiting, `p4` when idle, `p5` backlog. Missing means `p1`. The ticket MCP requires one on every filing.

### `claimed` and the assignee are cosmetic

They are still set and cleared, because the GitHub board and the mobile app
should read correctly — but **nothing in the protocol consults them.** The queue
filters on the claim lock; a Smith's stop predicate reads the claim lock; a
reap releases the claim lock. Two reasons they cannot be the lock:

1. `gh issue list --label` goes through a search index that lags the API by
   seconds. A just-claimed issue still lists as queued.
2. Every Smith in the fleet is the same login (`FLEET_LOGIN`) and the same
   label. Neither can change when an issue moves from one Smith to another, so
   neither can tell a Smith that it is no longer the owner.

A stale `claimed` with no lock behind it is harmless and gets tidied on the next
reconcile.

### Dependencies: use GitHub's native ones, not the issue body

An issue with an **open blocker is not queued.** When its last blocker closes it
becomes queueable on the next tick — no label change, no human step, nothing to
remember.

**A dependency is a first-class GitHub relationship, not prose.** Writing
"Depends on #28" in the Context section does nothing: the Operator does not read
the body for this, and it must not, because a body line cannot go stale when the
blocker closes. Set it in the issue sidebar (**Blocked by** → *Add blocked by*),
or:

```bash
# "#30 is blocked by #28"
gh api repos/codefrak-oss/frakworks/issues/30/dependencies/blocked_by \
  -F issue_id="$(gh api repos/codefrak-oss/frakworks/issues/28 --jq .id)"

gh api repos/codefrak-oss/frakworks/issues/30/dependencies/blocked_by --jq '[.[].number]'
```

Two footguns, both verified the hard way: the body wants the blocker's **global
integer `id`**, not its issue number, and it must be a JSON integer — `gh api -f
issue_id=…` sends a string and 422s, so use `-F`. Prose in Context is still
welcome as an explanation; it is just not the mechanism.

The rules the queue applies (`dispatch/lib/blockers.py`):

| Situation | What the queue does |
|---|---|
| every blocker closed, or none | queued |
| at least one **open** blocker | skipped, with `skip #30: blocked by #28` in the run log |
| the blocker lookup **failed** | skipped — "cannot tell" is a no. Claiming an issue whose blockers are unknown is the only direction that can start blocked work |
| a dependency **cycle** | every issue in it is skipped *and the ring is logged*. A cycle never resolves itself, so it is a human error to surface, not a filter to apply quietly |

GitHub rejects a direct `A ↔ B` dependency (422) but happily accepts a longer
ring, so cycles are a real state this repo can reach.

This replaces nothing about priority: `p0`..`p5` order the queue, blockers
remove things from it.

## Claiming

There is no atomic claim on GitHub **Issues** — but there is one on the **repo**,
and that is what the fleet uses.

**A claim is `refs/claims/issue-<N>`**, a git ref pointing at a parentless commit
whose message names the host, pid, `SMITH_ID`, start time and last heartbeat.
Creating a ref through the Git Data API is atomic on the server: N Operators
POST the same ref, one gets 201, every other gets `422 Reference already exists`
and moves on to the next issue. Nothing is re-checked, because there is nothing
to re-check.

This is what allows **any number of hosts to run the queue at once.** The
previous model needed exactly one process to be the only assigner; a second one
would have produced two assignments with no way to say which came first.

| Step | Mechanism |
|---|---|
| **Claim** | `POST /repos/:repo/git/refs` — 201 won, 422 lost the race |
| **Hold** | the Operator bumps the lock every `HEARTBEAT_SECONDS` while the Smith's pid lives |
| **Reap** | a lock unrenewed for `LEASE_TTL_SECONDS` may be taken by **any** host, via a `git push --force-with-lease` compare-and-swap so exactly one sweeper wins |
| **Release** | `DELETE` the ref, on `Done.` / `Blocked:` / `Returning to the Source:` / reap |

A claim comment is still posted, because nobody reads refs in a browser. It is
the human-readable copy of the lock and carries no authority of its own.

A **reaped lock whose Smith left no terminal comment** gets `blocked` and a
comment, exactly as before — whether it was reaped locally by the host that
started it or swept by a different host entirely.

A lock that is being renewed by a Smith that is alive but making no progress is
an **anomaly**, and the lease does not catch it: liveness is not progress.
Detecting that is still a human's job.

### The lease measures the host, not the Smith

The converse also holds, and it is the sharper exposure: **a heartbeat proves the
host can still reach GitHub, not that the Smith is healthy — and a missing
heartbeat proves neither is true.** The lease cannot distinguish a crashed Smith
from a working one whose host stopped running it. Anything that suspends the
Operator for longer than `LEASE_TTL_SECONDS` therefore reads to the rest of the
fleet as a dead Smith:

| What happens to the host | What the fleet sees |
|---|---|
| Sleep / suspend (a laptop idling on battery, lid closed) | heartbeat stops, lease expires, another host sweeps the lock |
| Shutdown or reboot | same, plus the worktree survives on disk with nothing running |
| Network loss longer than the TTL | same — the bump is an API call and it fails |
| The Operator loop killed while a Smith runs | Smith keeps burning tokens with nothing renewing its lock |

When the lease expires, any host may sweep it. The sweeper is doing exactly the
right thing with the information it has: it cannot see another machine's process
table, so an unrenewed lock is indistinguishable from a crash. It releases the
lock, clears the cosmetic `claimed` label, and requeues the issue. If the
suspended host then wakes, its Smith finds the lock gone and stops with
`Returning to the Source:` — the stop predicate is what keeps this from becoming
two Smiths on one issue.

The cost is the run: the tokens are spent and the work is lost unless the branch
was already pushed. **Observed 2026-09-07:** #124's Smith on a laptop lost its
lease when the machine idle-slept 47 seconds after a successful bump; the VM
swept it 10 minutes later, and 33 turns / ~$3.03 of completed work went with it,
having never been pushed.

Two properties of this are worth naming, because neither is obvious:

- **A host does not know it has been swept.** The Operator's own reconcile
  reports a Smith `healthy` based on the local pid, while the fleet has already
  declared it dead and requeued the issue. Both are locally correct and they
  disagree; nothing reconciles them until the Smith's next stop-predicate check.
- **Long tickets are the exposed ones.** A run that finishes inside one
  `LEASE_TTL_SECONDS` window is never at risk. The exposure scales with run
  length, not with host quality.

This is a property of leases in general, not a fault in any host. Any machine
that can be suspended has it — the fix, if one is ever wanted, is to make a
Smith's work recoverable (push early, resume from the branch) rather than to
make hosts stay awake.

After a sweep, the old Smith can keep acting for up to one `SMITH_STOP_TTL`
(default 60s) stop-hook cache window plus one in-flight tool call before its
next stop-predicate check catches the gone lock; `claim_and_start`'s live-Smith
guard (issue #129) is what stops the swept host's own next tick from
redispatching the issue into that window.

### Two kinds of ref live in `refs/claims/*`

Everything above describes an **issue claim**, which is a *lease*: it is
heartbeated, it expires, and a stale one is reaped. Two other ref shapes share
the namespace and behave in the opposite way, because they answer a different
question — not "who owns this issue" but "has this ticket already been filed":

| Ref | Written by | Question it answers |
|---|---|---|
| `refs/claims/issue-<N>` | `claim_acquire` | who owns issue N *right now* |
| `refs/claims/review-<child>-<pr>-<head-oid>` | `review_lock_acquire` | was a *fitness* review ticket already filed for this PR revision |
| `refs/claims/secreview-<child>-<pr>-<head-oid>` | `security_lock_acquire` | was a *security* review ticket already filed for this PR revision |
| `refs/claims/rebase-pr-<pr>-<head-oid>` | `rebase_lock_acquire` | was a rebase ticket already filed for this PR revision |
| `refs/claims/merge-<child>-<pr>-<head-oid>` | `merges()` | was the merge decision already taken for this PR revision |

All five are created through the same atomic create-if-absent POST
(`_claim_ref_create`), and that is the whole of what they share. `review-` and
`secreview-` carry the SAME key on purpose — same child, same PR, same head —
and are separate namespaces anyway, because the two reviews are independent by
design: one shared lock would let whichever reviewer filed first suppress the
other forever, and `reconcile_merges` needs both verdicts. The filing
locks are **tombstones, not leases**:

- **No heartbeat, no TTL, no sweep.** Nothing renews them and nothing reaps
  them. An expiring filing lock would re-open the very window it exists to
  close — the gap between a reviewer closing its own ticket and the PR being
  merged, during which the child still carries `needs-review`.
- **They are invisible to the lease machinery by construction.** `claim_list`
  and `lib/queue.py` scope *positively* to `matching-refs/claims/issue-` and
  `^refs/claims/issue-(\d+)$`, so a new key in the namespace is ignored by the
  sweep rather than needing to be excluded from it.
- **They accumulate**, one per filed ticket per PR revision, at the rate the
  fleet opens PRs. A parentless commit plus a ref is a few hundred bytes, and
  the default fetch refspec does not carry `refs/claims/*`, so no clone pays
  for them.

**The head commit is in every one of those keys on purpose.** A PR that is
reworked or repaired is a *new revision* needing a new ticket — and a new merge
decision, since the approvals the old one rested on were approvals of a diff
that no longer exists — and every action either ticket asks for writes new
commits on the head branch, so a repair that happened always frees the next
key, and one that has not always holds it. A
bare `<child>-<pr>` or `rebase-pr-<n>` key would be claimed permanently by
round one and silently swallow every later round, which is a worse and quieter
failure than the duplicate it prevents.

> **To ask for a PR revision to be reviewed or rebased again, delete its ref by
> hand.** That is the supported mechanism and the only one — nothing expires
> these. Pushing any new commit to the branch also asks again, by moving the
> head and therefore changing the key.
>
> ```
> gh api -X DELETE repos/<repo>/git/refs/claims/review-<child>-<pr>-<head-oid>
> ```

Smiths must not write any of these refs, filing locks included —
[smith.md](smith.md) §"Never touch a claim ref" covers the whole namespace.

The mechanics, the failure modes, and what was verified against the real API are
in the retired `operator.md` (git history), §"Claiming is an atomic git ref".

## Doing the work

The operating manual a Smith actually reads is [smith.md](smith.md) — the
Source. This section is the summary it implements.

A Smith working an issue:

- Comments **terse, state-focused** updates on the issue ("claimed", "blocked on
  X", "PR #123 opened"). Narrative — what was tried, why, dead ends — goes
  under the footer of the closing comment, not spread across the thread.
- Stays inside the scope written in the issue. If the task needs authority the
  Smith does not hold, it does **not** expand its own permissions — it fails up
  (see [delegation.md](delegation.md)).
- On finish, posts a **closing comment**, then adds `needs-review` and leaves the
  issue open if it opened a PR (the PR body carries `Closes #<n>`), or closes the
  issue itself if there is nothing to merge:

  ```
  Done. <one line: what changed>
  Commits: <sha> <sha>   PR: #<n>
  Deviations from the issue scope: <none | ...>

  — 🤖 Agent Smith `<SMITH_ID>` · issue #<n>
  ```

  Every comment, PR title and PR body a Smith writes ends with that footer line
  (`bots/smith.md` §5): the fleet posts as one machine account, so the footer is
  what names the individual Smith and run. A `wishlist:` line may follow that
  footer; nothing parses it yet.

- If it cannot finish: adds `blocked` (recoverable) or `needs-review` (needs a
  human decision), with a comment saying exactly what it needs.

### The three terminal comments are load-bearing

A Smith has exactly three ways to stop on purpose, and each one **must** begin
with its exact prefix:

| Prefix | Means | Issue left |
|---|---|---|
| `Done.` | finished, acceptance criteria met | open with `needs-review` if a PR was opened (the merge closes it); closed by the Smith only when there is no PR |
| `Blocked:` | cannot finish; needs a dependency or a human | open, `blocked` / `needs-review` |
| `Returning to the Source:` | the stop predicate tripped | open, unchanged |

The Operator's `reap` reads these prefixes to tell a deliberate exit from a
dead process. A Smith that stops **without** one of them is indistinguishable
from a budget kill or a crash, and gets marked `blocked` with its log tail
quoted into the thread. Say which of the three it is, or be treated as a
casualty.

Reap's scan for these prefixes is normally anchored on the current Smith's own
claim comment, so an earlier round's `Done.` can never be mistaken for this
round's exit. That claim comment is best-effort, though — when it failed to
post, the local reap (the host that ran the dead Smith) does not fall back to
the whole thread; it scopes the scan to comments posted at or after the
registry's own `started_at` for that run, so a crash on a rework round is
never misread as an earlier round's `Done.`. And when a Smith does post
`Blocked:` but forgets to apply the label itself, reap applies `blocked` on
its behalf rather than letting the issue requeue every tick with nothing to
stop it being claimed again.

The fleet-wide sweep (a *different* host reaping an orphaned lock) scopes the
same way, to the lock's own `claimed_at` — `claim_bump` and `claim_steal`
preserve that field's original value across every heartbeat renewal and every
reap rather than re-stamping it to "now" (issue #153), so it is a true
claim-time floor and not the moment of whichever renewal happened to be last.

## Filing new work

Any Smith that discovers work opens an issue — **without** `bot-task` (it lands
in human triage), with:

- a title that names the thing,
- acceptance criteria,
- the allowed scope (paths, services, spend),
- "stop and ask if…" conditions,
- a link back to the issue or comment where it surfaced.

The [`bot-task` issue template](../.github/ISSUE_TEMPLATE/bot-task.md) has these
fields. There are two ways to write one, and one of them is authoritative:

- **`mcp__bot-tickets__file_bot_task`** — the ticket MCP (retired 2026-09-27; the engine's `frakworks` MCP server files asks now), which was hosted
  on the Oracle at `https://<oracle-tailnet-name>/tickets/mcp` and
  reachable from a session with no shell at all (claude.ai on a phone). It is
  **the ticket-authoring contract**: `model`, `effort` and `priority` are
  required schema fields, the body is rendered from the template by one code
  path, and the issue is created **with `bot-task` + that priority** and no
  assignee — filing is releasing, and a Smith claims it within about a minute.
  `"dry_run": true` renders the title, body and labels without creating
  anything. Prefer it wherever it is available.
- **`gh issue create`** (the `/file-bot-task` skill that walked the fields was
  removed on 2026-09-26; a Claude Code session uses `submit_ask`). This is the fallback for a session
  without the MCP — the ticket is the same, but nothing stops you writing a
  malformed one.

Either way, do not hand-roll a body from memory: the fields below are what the
Operator and the reviewer both read.

The acceptance criteria are not optional and not decorative: run
`bots/check-acceptance-criteria <body-file>` before filing (the skill and
`bots/smith.md` §"Discovered work and sub-tickets" both require it). A checklist
that is empty, still the template's bare `- [ ]` boxes, a placeholder, or pure
opinion is not fileable — see §"Review and rework" for why.

### Intake tickets — the instant path

Since 2026-09-16 (#890) there is a second way to put an ask on the queue that costs the asker
**one call and no drafting**: an **intake ticket**. It is an ordinary
`bot-task`, released like any other, carrying the extra label `intake`, whose
Goal is fixed — *turn the ask below into a filed ticket, or ask what was
meant* — and whose body quotes the raw ask verbatim. A Smith claims it, reads
the repo on the fleet's time, and either

- **files the real ticket** through `file_bot_task` (criteria that pass
  `bots/check-acceptance-criteria`, a `Tier:` from `model-choice.md`, story
  points from `story-points.md`, a
  priority `p2`..`p5`, a `Context` line `Intake: <intake instance id>`); the
  engine rewrites the ask item in place into that ticket and leaves it
  open (one issue per ask) — no PR, nothing to merge; or
- **asks**: posts its questions as a comment, adds `needs-review`, and exits
  without closing. `needs-review` takes it out of the queue. The user answers
  in a comment and **removes the label** — that is the answer signal; the webhook
  wakes a host and a fresh Smith reads the whole thread.

Three ways to file one, all producing the identical stub:

| From | How |
|---|---|
| Claude Code | `submit_ask` on the engine's `frakworks` MCP server (`engine/README.md`, "Intake"; CLAUDE.md, "Filing tickets"). The `/ticket` skill was removed on 2026-09-26 and the `/intake` skill on 2026-10-10. |
| First mate, a Smith | `submit_ask` on the engine's `frakworks` MCP server (`engine/README.md`). The ticket MCP's `submit_ask` and its `tickets_mcp.intake` renderer were retired 2026-09-27 with the server. |

The stub's own scope is constant and read-only (tier 5, no PR, no commits),
and `intake` is read by nothing in `dispatch/` — the queue, the claim lock and
the reaper see a normal ticket. The refiner's rules, including the `p2` cap and
the fall-through to unlabeled triage for anything it can't do with current permissions,
are [`smith.md`](smith.md) §"Intake tickets". What it costs: one extra Smith
run per ask. What it buys: the ask is written by the person who has it, the
refining is done by a process that has the repo and nobody waiting on it, and
a clarifying question is a normal outcome rather than a stall.

### Refactoring tickets come from a job, not a person

A repo with refactoring enabled (`engine/README.md`, "Refactoring jobs") gets
small `feature` + `refactor` tickets filed by the engine's own job on its
schedule, each capped in changed lines and reasoned against
[`refactor-values.md`](refactor-values.md), the shipped values a repo can
replace with its own through `PUT /repos/{owner}/{name}/refactor`. They are
worked like any feature; a human authorizes them unless the repo set approval
to `none`.

### Choosing `Model:` and `Effort:` — read `model-choice.md` first

**Reading [`model-choice.md`](model-choice.md) is mandatory before writing a
`Model:` or `Effort:` line**, for humans and Smiths alike. In short: default to
`opus` at `Effort: medium` (or `low` for a tight checklist), `sonnet` for
mechanical work, and never two `opus`-at-`high` tickets on one account at once.
The doc has the measurements behind each rule and the 2026-09-14 evening that
prompted it.

**Both lines are mandatory and the Operator enforces both** (issue #592). A
ticket whose `Model:` or `Effort:` is missing, commented out, or outside
`VALID_MODELS` / `VALID_EFFORTS` is skipped, labelled `blocked`, and told which
line it needs. A blank `Effort:` is **not** a way to get `DEFAULT_EFFORT` any
more — that fallback survives only for the review, security-review and rebase
tickets the Operator files itself, which have no author to send back. Effort is
a spend decision and it belongs to whoever writes the ticket. Filing through
`mcp__bot-tickets__file_bot_task` makes this unfailable: its schema requires
both fields.

## Research tickets and proposals

Some tickets ask for research rather than a change: compare options, recommend,
stop. Their output is a **proposal**, not a description of what is running, and
it must not be confused with one — by a human reader or by the graph extractor,
which turns markdown tables into present-state facts.

So research output goes in a `docs/proposals/<slug>.md` doc, with a sibling
pitch `docs/proposals/<slug>.html`, never in the folder that describes what is
running. There is no index file: the engine's `GET /proposals` reads every
`docs/proposals/*.md` head on each target repo's default branch, and that is
what the desktop's Proposals branch lists. Each doc opens with a `Status:` line
(one of `proposed`, `accepted`, `shelved`, `rejected`, `built`), a
`Decision log:` and a `Ticket: #n`, as
[`../engine/README.md`](../engine/README.md) "Proposal" and the `GET
/proposals` row define. The graph extractor skips `docs/proposals/`.
**Merging the PR means "recorded", not "accepted"** — acceptance is a later
edit to the status line by a human, and the implementation ticket that follows
moves the durable facts into the present-state docs. Present state and history
never share a file. See [`../engine/README.md`](../engine/README.md)
"Proposal" for the exact shape.

**Never establish current mechanics from a proposal body.** How reviews are
filed, how merges are gated, what identities exist, what the Operator does on a
tick: read [`smith.md`](smith.md) and [`../engine/README.md`](../engine/README.md),
or the code. A proposal is
evidence of what was intended on the date in its `Decided:` line, and its body
is never revised afterward — `accepted` means approved, not "this is what runs".
An `Implemented in:` line names where the present state lives; go there.

A `research` ticket asks a question and wants an answer, not a change. Its
flow (`engine/README.md`, "Research") has a Smith answer it as an engine-kept
page, shown in the ticket's Answer section and never committed unless the
asker answers `save`, which lands it as a dated note under
[`bots/research/`](research/README.md). Measurements that ask for no decision
at all — a token breakdown, a timing study, a census of what Smiths actually
did — go in `bots/research/` too, one dated file each, with the scripts that
produced the numbers beside it. The graph extractor skips that
directory too. A research note never gets a status line, is never accepted or
rejected, and is never revised: a later measurement is a new file.

Something too half-formed to be any of those goes in
[`bots/ideas/`](ideas/README.md) — a seed and its open questions, one file each,
with a `Status: live | grown | dropped` header. An **idea** differs from a
proposal in substance: it carries no options matrix, no recommendation and no
plan, because it is written before the shape of the thing is known well enough
to compare options; an idea that has those is a proposal. It differs from a
research note in kind: a note measures something that already exists and is
never decided, while an idea is a design that does not exist yet and ends by
growing into a proposal or being dropped. **The idea stage is optional and is
never a gate** — a proposal may be written with no idea before it, and nothing
requires one. That directory is skipped by the graph extractor too.

There is no list of current proposals here: there is no proposal index
file, and the engine's `GET /proposals` reads every `docs/proposals/*.md`
head on each target repo's default branch, which is what the desktop's
Proposals branch shows. Look there, or at `docs/proposals/` in the repo,
for what exists now.

## The army's own domain: `<agent-domain>`

Since 2026-09-18 the Smiths have a corner of the public internet that is theirs
by DNS delegation, not by convention: a subdomain of the operator's root domain
(`<agent-domain>`). The root domain is the
operator's (Cloudflare Registrar + DNS, catch-all to their inbox); the
subdomain is NS-delegated to deSEC, where a fleet token can write records
inside it and nowhere else. The user's deployment documents its own layout,
records and tokens (`network/codefrak-domain.md` there), with the filled
values, outside this repo.

Two things live there today:

- **Role GitHub Apps** — `github-apps/` (removed 2026-09-24 with the Operator;
  the Apps still exist on the org, and the provisioning, minting and smoke-test
  scripts are in git history). The fleet's
  identities on the target org: coder, pr-reviewer, sec-reviewer and
  merger Apps, what GitHub enforces for each, the `two-approvals` check and the
  two rulesets that carry the rest, key custody, and the provisioning, minting
  and smoke-test scripts. Proven end to end 2026-09-18; not yet used by the
  Operator.
- **Agent mailboxes** — `agent-mail/` (removed 2026-10-02; the Cloudflare
  Email Worker and its D1 schema are in git history). Any alias at the
  subdomain was stored with links and one-time codes extracted, behind a
  bearer-authenticated API, and could send through Resend. Proven
  2026-09-18; nothing on the engine's path used it.
- **DNS records** the fleet may create for itself (hostnames, verification TXTs,
  ACME DNS-01) — via the fleet deSEC token, ideally behind an Oracle MCP tool
  with an audit log. Also not built yet.

## Votes

Some questions want N independent answers rather than one: a **mass vote** fans
one question out as N near-identical `bot-task` tickets — the ballots — each
answered in MDR by whichever Smith claims it, plus one tally ticket
`Blocked by` all N that reduces the answers to a count and writes it back by
PR. A ballot has no paths and no PR and closes itself with `Done.`; nothing in
`dispatch/` knows what a vote is.

The vote-file format, the ballot body, the two-line `capture_thought` grammar
the answers use, the reduction rules and the hard cap of 20 ballots per vote
are in [`votes/README.md`](votes/README.md), beside the vote records
themselves. A vote was opened with one call to `open_vote` on the tickets MCP
(`matt` role), which filed and wired all N + 1 tickets, paced; that server was
retired 2026-09-27 and nothing replaces `open_vote` yet.

## Review and rework

A Smith's PR is reviewed before merge — by a human, a stronger model, or a
no-tools reviewer. Feedback goes on the
**PR** as a review with inline comments, never in the issue thread. The issue
gets one terse line: "changes requested on PR #n".

**A reviewer that finds no acceptance criteria requests changes to the
ticket, not the PR.** The acceptance criteria are the whole contract a
context-free reviewer judges against.
If the child ticket has none — or only placeholders, or restatements of the
Goal, or criteria nobody but the author could settle — the review does not try
to guess what "done" meant and grade the diff against the guess. It says the
ticket is underspecified, names what a verifiable criterion would look like, and
requests changes so a human can fix the ticket before the work is re-judged.
`bots/check-acceptance-criteria` is the same bar, applied at filing time.

**A reviewer files no tickets** (the user, 2026-09-16). A concern about the ask
— the PR does not fully deliver the child's Goal, or leaves something wrong or
stale behind it — is `REQUEST_CHANGES` even when every criterion is met; it is
never an approval plus a follow-up ticket, because that is how a partial
solution merges as if it were the whole one. An observation unrelated to the
ask is a note in the review body marked *unrelated, not blocking*, plus an MDR
thought, and a human decides whether it becomes a ticket. Reviewer preference
still blocks nothing. The review ticket the Operator files carries this as a
criterion ([`smith.md`](smith.md) §"Discovered work").

**Rework is a requeue with the PR attached.** There is no rework state and no
new label. The reviewer removes `needs-review` (if set) and leaves the issue
queued. A Smith picking up an issue that already has an open PR referencing it
treats addressing that review, on that branch, as the job — it does not start
over. Rounds repeat until the PR merges and the issue closes. **A rework round
finishes with the identical `Done.` closing comment any other success uses**
([`smith.md`](smith.md) §7) — its first word is machine-read by the reaper,
never a paraphrase like "Rework round done." (issue #1218 / PR #1219,
2026-09-20: a clean finish worded that way was filed as a crash). If the PR's own
closing keyword doesn't close the issue — the merging identity lacking Issues
permission, most often — `reconcile()` notices the merged PR on an open
`needs-review` child on its next tick, comments once, clears the label and
closes the issue itself.

**Exactly one review ticket is filed per PR *revision*.** The Operator files a
review ticket when a child reaches `needs-review` with an open PR, and a
`refs/claims/review-*` filing lock keyed on the PR's head commit guarantees it
happens once: two hosts cannot both file it, and it is not re-filed in the gap
between the reviewer closing its own ticket and the merge. Because rework pushes
new commits, it moves the head and therefore files round two normally — the lock
suppresses duplicates, never a genuine next round. An `APPROVE` does not move the
head, which is exactly why the window after one stays shut. See §"Two kinds of
ref live in `refs/claims/*`" for the namespace and for how to ask for a re-review
of an unchanged PR.

**A `needs-review` child whose PR head is unchanged since its review ticket
closed gets a comment, not a second review.** The filing lock is a tombstone,
not a lease — nothing reaps it — so a child that comes back to `needs-review`
(or never left it) at the *same* revision a review ticket was already filed
and closed for will not get another one filed. This is the ordinary shape of a
rework round that replied with no new commit, a reviewer clearing
`needs-review` before submitting its verdict, a human clearing `blocked` on a
PR that never changed — or, the most common of the four and not actually stuck
at all, a reviewer `APPROVE`d the PR and it is simply waiting on the merge
workflow (`.github/workflows/merge.yml`, next review event or the 15-minute
schedule).
Until now the only evidence was a per-tick Operator log line nobody reads
between ticks. Instead, `reconcile_reviews` posts one comment on the child,
naming the head sha and the exact `refs/claims/review-<child>-<pr>-<head>` ref
to delete for a fresh review — the `gh api -X DELETE` command is the same one
named above. It is posted at most once per revision: a repeat tick finds its
own earlier comment (matched on a fixed first line naming the head) and posts
nothing. The phase also defers entirely — no filing, no comment — while the
child still holds its own claim lock, the same way `reconcile_rebases` defers
on its source issue: a live lock means a Smith is writing that branch right
now, which is not a safe moment to evaluate it.

A lock that already exists is not always that tombstone, though — it is also
what a second host sees, seconds into the same tick, when it loses the race
for the SAME child+PR+head to another host that just won it (the race two
hosts cannot both win, above). There the review ticket is open, under a number
this host has never seen, and claiming it was "already filed and is no longer
open" is false. `reconcile_reviews` tells the two apart from the lock commit's
`filed_epoch`, compared against the instant its own dedup corpus was read this
tick (issue #149): a lock filed after that instant is the race, not a
tombstone, and gets no comment at all — the loser's own corpus read next tick
will carry the winner's marker before this phase ever reaches it again. A lock
filed at or before that instant is a real tombstone and gets the comment
above, unchanged. If the one extra check this costs cannot itself be answered,
the comment is still posted but hedges: it says a filing lock exists and no
open ticket was seen this tick, and stops short of claiming the ticket is
closed.

The comment tells the `APPROVED`-and-waiting case apart from an actually stuck
revision: `reviewDecision` rides along on the same `gh pr list` call that
already fetches `headRefOid`, at no extra API call. An `APPROVED` PR gets
prose that names the real cause — waiting on the merge, not stuck — and does
not lead with the delete command, since deleting the lock on already-approved
work re-opens the exact window the lock exists to keep shut.

**Review/rework rounds are capped at `REVIEW_MAX_ROUNDS`.** A reviewer and a
Smith that disagree could otherwise alternate indefinitely, each round costing
a full reviewer run and a full rework run, until a human noticed and pulled
`bot-task`. The round count for one child+PR is `REVIEWER_LOGIN`'s own
`CHANGES_REQUESTED` verdicts on the PR, read fresh each tick from `GET
/repos/{repo}/pulls/{pr}/reviews` (issue #403). It is deliberately not the
number of `refs/claims/review-<child>-<pr>-*` filing locks the PR has
collected: that count is filings, and a PR the *security* reviewer keeps
rejecting collects a fresh filing lock per rework round without the fitness
reviewer ever objecting, which used to be able to trip this cap on a PR this
reviewer had no complaint about. The filing locks are unchanged otherwise —
still taken before every fitness review ticket is filed, still what dedups a
filing per head oid — they are just no longer what this cap counts. At or
above `REVIEW_MAX_ROUNDS` (`dispatch/config.sh`, default 10), `reconcile_reviews`
files no further review ticket, labels the child `blocked`, and posts one
comment — deduped per child+PR, not per revision, since the cap is on the PR
as a whole — naming the round count and the way to continue: raise
`REVIEW_MAX_ROUNDS`. A failed verdict count is fail-closed for this cap only,
in the opposite direction from every other check in this phase: the count is a
limiter on top of the lock's own correctness, not a gate the review depends
on, so the review is still filed and the failure is only logged. The cap does
not apply once the PR's `reviewDecision` is `APPROVED` — that is the ordinary
wait for the merge workflow, not the alternating reviewer/rework loop the cap
exists for, and the child falls through to the same `APPROVED` wording
described above instead of being labeled `blocked`. If an earlier tick had
already tripped the cap and labeled the child `blocked`, reaching `APPROVED`
also clears it: `reconcile_reviews` removes `blocked` and posts one comment
saying the cap no longer applies, matched by its own first line so it fires at
most once per child+PR, and only when this cap's own comment (not an unrelated
`blocked`) is the one on the thread.

**Rejecting output is also a moment to re-examine effort and model.** A
requeue re-runs at whatever the ticket specifies, so an unchanged ticket gets
round one's capability again. Before requeueing, the reviewer decides which
kind of failure this was:

| Failure | Remedy |
|---|---|
| **Execution** — a criterion was skipped, scope was exceeded, the work is sloppy or incomplete | Requeue as-is. The ticket was right; the run was not. |
| **Capability, thin reasoning** — the pass looks rushed rather than genuinely out of reach: shallow but on the right track | Add or raise the `Effort:` line in Allowed scope, then requeue. Cheaper than a model swap, and the first thing to try. |
| **Capability, out of reach** — the reasoning is thin even at max effort, the recommendation is unjustified, the tradeoffs were not really weighed | Add or raise the `Model:` line in Allowed scope, then requeue. |

Raising effort or the model to paper over an execution failure hides it — a
Smith that ignored the acceptance criteria will ignore them on a stronger
model too. The inverse is just as wasteful: requeueing a judgment-shaped
ticket unchanged tends to reproduce the same answer and burn another review
cycle. Research tickets, and anything whose deliverable is a recommendation
rather than a change, are the usual capability case (see "Research tickets
and proposals" above). The `Model:` and `Effort:` lines live on the ticket,
not the Smith.

**`Turns:` and `Budget:` are the same idea for the two global run caps.** A
ticket expected to run long, or one that needs more notional spend headroom
than the host's `MAX_TURNS` / `MAX_BUDGET_USD` default, can raise either with
its own `Turns: <int>` / `Budget: <dollars>` line in Allowed scope — resolved
by `op/operator.py`'s `resolve_turns` / `resolve_budget` the same way
`resolve_model` / `resolve_effort` read `Model:` / `Effort:`. Neither is
mandatory and an absent or unparsable line just falls back to the host's
default. A ticket cannot ask for an unbounded amount either: each is clamped
to a host ceiling (`MAX_TURNS_CEILING` / `MAX_BUDGET_USD_CEILING` in
the retired Operator's config table), and a clamp is logged. This is the
per-ticket fix for the interim workaround from #192/#204, where the only
remedy for hitting `error_max_turns` was raising the global cap on every host
by hand.

**The reviewer runs one rung above the model that wrote the code.** Every ticket
that reaches `needs-review` with an open PR gets a review ticket, and the model
on it is not the ticket's own: the Operator reads the ticket's `Model:` line and
files the review at the next rung up `MODEL_LADDER` in `dispatch/config.sh`
(`haiku sonnet opus`, weakest first). The ladder **saturates at the top** — an
`opus` ticket is reviewed by `opus`. That is a real review, not a formality: a
same-model reviewer still catches every *execution* failure in the table above,
because those need a fresh session reading the ticket and the diff against each
other, not more capability. It is the *capability* failures that need a rung,
which is what the rungs below the top are for.

The ladder is deliberately code and not a ticket field: the rule is
deterministic, and a second mandatory model line would be a second place for the
placeholder bug that made `Model:` mandatory (#67/#68). `fable` is on no rung on
purpose — ranking it against `opus` is not something a lookup table can honestly
do. Every *other* off-ladder model has no successor and so gets **no** review
ticket, and the Operator log says so.

**`fable` reviews itself, one effort step up.** A `fable` ticket with no
`Review model:` line is reviewed by `fable`, with the review ticket's `Effort:`
one step up `VALID_EFFORTS` (`low` → `medium` → `high` → `xhigh` → `max`,
saturating at `max`) from the effort the *implementer* ran at — the child's own
`Effort:` line if it is valid, otherwise `DEFAULT_EFFORT`. This is the only
review whose effort is not copied from the parent, and the only off-ladder model
with an answer. The step is on the **effort** axis, not the model axis, because
that is the one a same-model review can honestly move along: more thinking on
the read than went into the write. Before this (#377) a `fable` ticket had to
carry a hand-written `Review model: fable` to be reviewed at all. Set
`FABLE_SELF_REVIEW=0` on a host to go back to no review ticket; an explicit
`Review model:` is an author's stated opinion and is never stepped up.

**`Review model:` is the escape hatch**, an optional line in a ticket's Allowed
scope for an author with an actual opinion:

```
- Model: haiku
- Review model: opus
```

When present and recognised it is used verbatim and the ladder is not consulted;
when absent, blank, or not a valid model it is ignored (with a log line) and the
ladder applies. Apart from `fable`, it is the only way to get a review ticket
for an off-ladder model. Setting `REVIEW_LADDER_ENABLED=0` on a host files the
review at the ticket's own model instead of a rung up — it does not, and must
not, turn the review ticket off.

**A ticket does not need a fleet-authored parent to be reviewed.** Review
tickets started as a way to give a Smith's sub-ticket a reviewer once its
requester was gone, and detection required both the child and an open parent to
be fleet-authored — which meant a ticket the user filed themselves reached
`needs-review` and waited for the user, review-by-submitter with nothing in between.
Carrying `bot-task` is now the whole membership test. A ticket with a
fleet-authored parent still embeds that parent as the requester's intent; one
without is reviewed against its own body, because the ticket is the whole
contract a context-free reviewer judges against either way.

The reviewer runs as `REVIEWER_LOGIN` — a second fleet account, not the human
operator's login — and the fleet authors PRs as `FLEET_LOGIN` (identity.md was
part of the proposals folder under `bots/`, removed from this repo in the
2026-09-22 move),
so a real `REQUEST_CHANGES` verdict is accepted.
The verdict is no longer advisory: two approvals pinned to the current head are
what a **GitHub Actions workflow** (`.github/workflows/merge.yml`) merges on, and
a ruleset on `main` makes the merger App (`<merger-app>`) the only identity that can land a
commit on `main` at all. Nobody clicks merge, and no fleet host holds a
credential that could.

### The second review: `smith-security`

**Every fleet PR also gets a second, independent review, from a third
identity.** `reconcile_security_reviews` (phase 4b, `dispatch/op/reconcile.py`)
runs immediately after the fitness pass over the same `needs-review` +
`bot-task` children and files its own `bot-task` + `p0` ticket per PR revision.
The reviewer answers a **different and narrower question** — not "does this do
what the ticket asked" but "does this introduce an inappropriate security
implication": secrets, widened bot authority, untrusted input reaching a shell
or the network, exposure of the hosting network. It has no opinion on ticket fitness and its
ticket tells it to render none. The security rubric is
embedded in the ticket verbatim, and it asks for exactly one verdict —
`gh pr review --approve` or `--request-changes` — nothing advisory.

**An idea-shaped PR gets neither review, and merges without them.** A PR whose
whole diff is one new `bots/ideas/<slug>.md` plus its index entry in
`bots/ideas/README.md` is skipped by both review phases, and the merge gate
waives its two approvals while keeping every other check. The exact shape and
the reasoning were in the retired `operator.md` §"Reviews and the merge gate" (git
history).

The point of the pair is `reconcile_merges` (a later ticket): a merge gated on
"both identities independently approved the current head sha" needs two
verdicts to check, which is why this is a second agent and a second ticket
rather than a second section in the fitness reviewer's rubric.

| | fitness review | security review |
|---|---|---|
| marker (first line) | `Reviews: #<child> PR #<pr>` | `Security-reviews: #<child> PR #<pr>` |
| filing lock | `refs/claims/review-<child>-<pr>-<head>` | `refs/claims/secreview-<child>-<pr>-<head>` |
| identity | `REVIEWER_LOGIN` / `REVIEWER_TOKEN_FILE` | `SECURITY_LOGIN` / `SECURITY_TOKEN_FILE` |
| model | the ladder, or `Review model:`, or `fable` self-review | whatever the engine's tier map gives for its security tier (`GET /security/tier`; `bots/tiers.toml`'s `security_tier` until 2026-10-05) — the ticket's own `Model:`, `Review model:` and `MODEL_LADDER` do not apply |
| effort | the parent's `Effort:`, else the ticket's, else `DEFAULT_EFFORT` — except a `fable` self-review, which is one step above the ticket's | the same row of the map — the child's `Effort:` does not apply |
| round cap | `REVIEW_MAX_ROUNDS`, counting `REVIEWER_LOGIN`'s own `CHANGES_REQUESTED` verdicts | `SECURITY_MAX_ROUNDS`, counting `SECURITY_LOGIN`'s own, independently |
| run-log phase / metric `kind` | `review` / `kind="review"` | `secreview` / `kind="security-review"` |

Everything else behaves the way the fitness review does, deliberately: same
atomic filing lock keyed on the PR's head commit, same "delete the ref by hand
to ask again", and **a security `REQUEST_CHANGES` removes `needs-review` from
the child exactly as a fitness one does**, leaving it queued with the PR
attached, so the existing rework path runs unchanged. The direction is worth
stating: `needs-review` is what holds a child **out** of the queue
(`dispatch/op/queue.py` `EXCLUDE`), and phase 4b only ever looks at children
that already carry it — so "requeue" means taking the label off, and a
security reviewer that adds it instead stalls the rework its own verdict just
asked for.

Two things are kept apart on purpose. **The counters are independent** — a
security `REQUEST_CHANGES` must not spend the fitness reviewer's budget, or
three security findings in a row would leave a PR `blocked` with no fitness
review left and a comment thread that does not explain why. **The passes are
independent** — a fitness review that cannot be filed this tick (an unreadable
body, no valid `Model:`, an off-ladder model like `claude-opus-5`) must not also
cost the PR its security review. That last one is the visible difference in the
table above: an off-ladder ticket gets **no** fitness review ticket and still
gets its security one, because every PR gets a security review and only the
depth is ever scoped, never the decision to review at all.

Only phase 4 posts the stuck-state comment described above; phase 4b sees the
same child, PR and head on the same tick, and a second comment saying the same
thing about the other lock is noise rather than a second piece of information.

**The Operator is meanwhile writing down what it *would* merge.** An eighth
reconcile phase, `merges()` (`dispatch/op/reconcile.py`, issue #287),
decides the merge deterministically and then does nothing with the decision. For
every open fleet PR it reads one `gh pr view --json
reviews,mergeable,mergeStateStatus,headRefOid,isDraft,files,statusCheckRollup`
and asks whether the
latest review from **both** `REVIEWER_LOGIN` and `SECURITY_LOGIN` is `APPROVED`
**on the PR's current head commit** — not `reviewDecision`, which collapses
every reviewer into one aggregate value and would let an approval of an earlier
commit count for the diff that replaced it. A `CHANGES_REQUESTED` from either
identity on that head blocks, the PR must be `MERGEABLE` and not a draft, and
the child must not carry `blocked` (the proposal's decision that `blocked` *is*
the hold, so the user holds any PR by adding it).

**And the CI gate must be green on that same head.** A PR that touches
`dispatch/**` (or `.github/workflows/dispatch.yml`) is not a candidate until
every check run of the `dispatch` workflow on the head being merged reports
`COMPLETED` / `SUCCESS`. Missing, still running, red, or a rollup the API did not report
each log one `merges: skip … : ci=<state>` line and wait. Reading `files` and
`statusCheckRollup` from the *same* `gh pr view` as `headRefOid` is what pins
the answer to the revision the two approvals are pinned to; a second call could
describe a commit pushed in between. A PR that touches nothing under
`dispatch/` has no such run and is unaffected.

That gate is the deterministic half of a promise that used to be prose. A
Smith's "every test still passes" in a PR body is written by the Smith whose
work is being judged, so both roles are now asked to produce the run instead:
the implementation Smith's prompt gains a step telling it to run
`python3 -m compileall -q dispatch/op dispatch/tests` and
`python3 -m unittest discover -s dispatch/tests` and paste the `Ran N tests … OK`
line into the PR body — but only when the ticket's **Allowed scope** names a
`dispatch/` path, since those are exactly the PRs the workflow gates — and the
review ticket gains a box telling the reviewer to run the suite itself at the
PR's head and treat a red run as `REQUEST_CHANGES` whatever the body claims.
Three independent runs of the same suite, and only the workflow's own is
allowed to decide the merge.

Everything else — a failed call, a
field GitHub did not report, a lock that could not be taken — is not a candidate
and is logged. When a PR clears all of it, the phase takes
`refs/claims/merge-<child>-<pr>-<head>` with the atomic create-if-absent
primitive the review and rebase locks already use and logs exactly one line:

```
merges: would merge #287 (PR #301, both approved on 8f2a…)
```

The lock is what makes that "exactly one" true across hosts and across ticks;
a PR that misses on some other check logs one `merges: skip …` line naming the
check instead. Nothing else happens on the Operator's side — no comment is
posted, no label is touched.

> **Not in frakworks.** `merge.yml` and `merge-trigger.yml` were removed on
> 2026-09-24: this repo never had the merger App's secrets or the `main`
> ruleset, so the workflow never merged a PR here. PRs are merged by hand
> or by the session that opened them. The text below describes the
> setup of the private repo this one was moved from.

**The merge itself is step 4, and it does not run on a fleet host.** It is
`.github/workflows/merge.yml` (issue #342), triggered by `workflow_run` of
`merge-trigger.yml` — a no-permissions, no-secrets workflow that fires on
`pull_request_review`, and on a PR being opened or pushed to, which is the only
event a PR that gets no review ever produces — by `workflow_run` of the
`dispatch` CI workflow, so a
PR approved before its checks went green merges when they do, and by a
`*/15` schedule as a backstop (which GitHub throttles on a quiet repo to once
per ~3h, so it is a backstop and not a latency bound). Two files
because GitHub runs a `pull_request_review` workflow from the PR's own copy of
the file, with secrets; `workflow_run` and `schedule` always run the copy on
`main`. The job checks out `main` — never the PR head, so a PR cannot judge
itself by its own copy of the rules — and runs
`python3 -m op merge-decide`, which is **the same `Reconcile.merge_decision` the
Operator's dry run calls**, not a second implementation of the gate. Only a
decision of `merge` mints a token, with `actions/create-github-app-token` from
the merger App's `MERGER_APP_ID` / `MERGER_APP_PRIVATE_KEY`, and only
that one step gets it:

```
$ python3 -m op merge-decide --pr 306
{"decision": "merge", "reason": "both approvals pinned to 9ba94206af86, …",
 "pr": "306", "child": "228", "head": "9ba94206af86…"}
```

It is an App token and not `GITHUB_TOKEN` on purpose: a merge made with
`GITHUB_TOKEN` does not trigger `on: push`, which would silently stop `graph.yml`
and `dispatch.yml` on every bot merge. `concurrency: merge-main` is the
workflow's equivalent of the cross-host merge lock, the job's `permissions:` are
read-only, and the credential is revoked when the job ends — so there is nothing
on a Smith host or a workstation to inherit or leak.

`MERGE_MODE` stays at `dry-run` on every host permanently. The Operator's phase 6
is now the **audit shadow** of the workflow: it runs the identical decision every
tick and logs `would merge` beside what the workflow actually did, so a
divergence between the two is visible in the run log rather than only in the
merge history. `live` (`dispatch/config.sh`, issue #301) still works and is no
longer the plan.

> **The fleet PAT cannot touch this.** It carries `repo` scope and not
> `workflow`, so GitHub refuses any push from a Smith that creates or updates a
> file under `.github/workflows/`. `merge.yml` and `merge-trigger.yml` were
> pushed from the human owner's own credential; any later change to them goes the same way.

**An approved PR that goes `CONFLICTING` is repaired the same way — with a
ticket.** Once a PR has been reviewed nothing looks at it again, so a branch that
goes stale while it waits for a merge just stops moving, invisibly (#89, observed
on PR #78). Each tick the Operator lists open PRs authored by the fleet and files
one `bot-task` + `p1` **rebase ticket** for any whose `mergeable` is
`CONFLICTING`, naming the PR, its head branch and the base to catch up to. Only
`CONFLICTING` counts: `UNKNOWN` is what GitHub reports while it is still
computing the merge commit, and it resolves itself within a tick or two. The
ticket is deduplicated on a `Rebase: PR #<n> onto <base>` marker on its first
line, and none is filed while the branch's own issue holds a claim lock — a Smith
is writing that branch right now. The Operator files the ticket and nothing else;
it does not rebase branches itself. Once filed, the rebase ticket also sets a
native `blocked_by` dependency (§"Dependencies") on its source issue, so the
source cannot be requeued and picked up by a rework Smith on the same branch
while the rebase ticket is still open (#128).

## Human triage

**Filing is releasing** (the user, 2026-09-15). `file_bot_task` on the
ticket MCP (retired 2026-09-27; the engine's `frakworks` MCP server files asks now) created the issue with `bot-task` and a required
priority (`p1`..`p5`, never `p0`) for every role that may call it — the user from
a device, a Smith filing discovered work, jiminy. The author's judgement is the
gate: the caller has the task in front of it and is the one who should size
and prioritise it, and the schema makes a ticket without a valid `Model:`,
`Effort:` and priority unwritable. A Smith's limits are unchanged and are in
[`smith.md`](smith.md) §"Discovered work": scope no wider than its own, never
`p0`.

The unlabeled inbox still exists for tickets filed by hand with `gh`. A human
reviews scope and acceptance criteria and adds `bot-task` (and a priority) to
release one.

That release is also a tool on the ticket MCP:
**`release_ticket`**, which is `matt`-only — no machine role unlocks it, and a
`smith` or `jiminy` token is not even told it exists. It takes two calls. The
first previews the ticket's goal, criteria, scope and the exact labels it would
add, writes nothing, and returns a five-minute HMAC confirm token; the second
applies exactly `["bot-task", <priority>]`. The token is bound to the issue,
the priority, **and a digest of the issue as it was at preview time**, so if the
body or labels moved in between the confirm is refused and you re-read the text
you are approving. `priority` is `p1`..`p5` — `p0` is not an accepted value,
because "drop everything" is a judgement about the whole queue and is made at a
keyboard. What the confirm step buys is legibility, not authentication: it
exists so releasing work from a phone is a thing you read before it happens.

Since 2026-09-12 a Smith may also release the discovered work it files, within
the limits in [`smith.md`](smith.md) §"Discovered work and sub-tickets": the
new ticket's scope fits inside its own, priority is `p1`..`p5` never `p0`, the
`Model:` and `Effort:` lines are its own judgement, and open dependencies are
declared with "Blocked by". Since 2026-09-15 that release happens on filing.
Anything needing wider scope is filed with `gh`, unlabeled, and lands here.
Removing `bot-task` from an in-flight issue is a soft stop — the Smith's own
stop predicate catches it at the next check and it returns to the Source.
Deleting its claim lock is the harder version of the same thing, and works from
any machine with `gh`.

## Design principles

Backend-independent, non-negotiable:

1. **Every Smith has a stop predicate before it starts.** A task with no
   definition of "done" is not queued. (The broom had no stop condition.)
2. **Spawn depth and breadth are capped and attenuable.** A Smith cannot mint a
   sub-Smith with authority — or budget, or TTL — it does not itself hold. See
   [delegation.md](delegation.md).
3. **Ownership is an atomic lock or it is not ownership.** Labels, assignees
   and comments are decoration. If two workers can both believe they hold a
   thing, they will.
4. **Shutdown is revocation, never a mid-task kill.** Close the issue, pull
   `bot-task`, or delete the claim lock; the Smith checks and exits. Nothing
   needs to reach into the Smith's process.
