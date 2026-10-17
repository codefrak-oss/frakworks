# The post-deploy check

A chore, bug or feature whose PR carries a `## POST-DEPLOYMENT-CHECK` section
(`bots/smith.md`, "The post-deployment check") is not done at its merge: the
flow calls `bpmn/post-deploy-check.bpmn` (`call-post-deploy-check`) and stays
open until the change has been checked in production (#100007). A PR without
the section skips it, as before. Overview: [engine/README.md](../README.md),
"Deploys".

<svg xmlns="http://www.w3.org/2000/svg" width="760" height="230" viewBox="0 0 760 230" font-family="sans-serif" font-size="12">
  <defs><marker id="a" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="#555"/></marker></defs>
  <g fill="#eef3fb" stroke="#4a6fa5">
    <rect x="10" y="40" width="110" height="44" rx="8"/>
    <rect x="150" y="40" width="130" height="44" rx="8"/>
    <rect x="310" y="40" width="120" height="44" rx="8"/>
    <rect x="460" y="40" width="130" height="44" rx="8"/>
    <rect x="310" y="150" width="120" height="44" rx="8"/>
    <rect x="460" y="150" width="130" height="44" rx="8"/>
  </g>
  <g fill="#fdf1e2" stroke="#c07a1c"><rect x="620" y="150" width="130" height="44" rx="8"/></g>
  <g fill="#e6f4ea" stroke="#2e7d32"><rect x="620" y="40" width="130" height="44" rx="8"/></g>
  <g text-anchor="middle" fill="#222">
    <text x="65" y="60">merged</text><text x="65" y="75">set the deadline</text>
    <text x="215" y="60">in production?</text><text x="215" y="75">wait for the deploy</text>
    <text x="370" y="60">check it</text><text x="370" y="75">(Smith, mode check)</text>
    <text x="525" y="60">outcome?</text><text x="525" y="75">passed / failed</text>
    <text x="370" y="170">when next?</text><text x="370" y="185">wake at / event</text>
    <text x="525" y="170">not yet</text><text x="525" y="185">wait, then re-check</text>
    <text x="685" y="170">deadline: ask you</text><text x="685" y="185">wait / drop / bug</text>
    <text x="685" y="60">close the ticket</text><text x="685" y="75">(a bug filed on failed)</text>
  </g>
  <g stroke="#555" fill="none" marker-end="url(#a)">
    <path d="M120,62 H148"/><path d="M280,62 H308"/><path d="M430,62 H458"/><path d="M590,62 H618"/>
    <path d="M525,84 V148"/><path d="M460,172 H432"/><path d="M370,150 V86"/>
    <path d="M685,150 V86"/>
  </g>
  <text x="215" y="110" text-anchor="middle" fill="#555">deploy event, or every 12 h</text>
  <text x="600" y="215" text-anchor="middle" fill="#555">the deadline timer interrupts any wait</text>
</svg>

## The section

`PostDeploySection.parse` reads the section: the heading at level 2 or 3, its
prose the `what` the check verifies, and two optional keys at line start,
`Re-check:` (the first wait between checks: `30m`, `4h`, `2d`, `1w`) and
`Deadline:` (a duration from the merge, or an ISO date or instant). Grammar and
the brief: [smith-runs.md](smith-runs.md), "The check mode".
`task-check-post-deploy` reads it before the merge. When the section cannot be
read, or the repo has no `ops` server to say when the merge is in production,
the merge waits for the user (the hitlp merge question names why); their
accept runs the task again.

## Waiting for the deploy

`task-init-check` sets `checkDeadlineAt`. `task-poll-deployed` asks
`DeployedChecks.isDeployed(repo, mergeSha)`: the engine's own build commit for
its build repo, the repo's `ops` MCP server (the `ops/1` contract,
[feature.md](feature.md), "The ops role") for any other. Not yet deployed, the
flow waits at `receive-deploy-reached` for a deploy event
(`POST /repos/{owner}/{name}/deploys`, [deploy/k8s/README.md](../../deploy/k8s/README.md)
has an Argo CD template that posts one), with a safety poll every 12 h
(`event-safety-poll`) for a deploy nobody reported. A deploy whose sha is
`mergeSha`, or that `DeployedChecks` says carries it, wakes the flow.

## The check loop

Once deployed, `call-check` starts a Smith in mode `check` at `check.tier`. It
answers `passed` (the loop ends at `end-check-passed`), `failed`
(`task-file-check-bug` files a bug, `end-check-failed`) or `not_yet`.
`task-next-check` sets `checkWakeAt` from the run's wake hint, else the
section's `Re-check`, else 1h, 4h, 1d, then daily; the flow waits at
`event-check-at`, or with an event hint at `receive-check-event` with
`event-check-fallback` as the fallback timer, then checks again.

## The deadline

`event-deadline` (at `checkDeadlineAt`, default 7 days after the merge)
interrupts the deploy wait and the check loop alike. `task-post-deadline-question`
files a HITLP decision with three options and the flow waits at
`user-task-answer-deadline`: `wait` moves the deadline on and goes back to
waiting, `drop` closes without a bug, `bug` files one
(`task-file-deadline-bug`). Every end closes the ticket (`task-close-ticket`).

## The ends

| End | How it reads on the desktop |
| --- | --- |
| `end-check-passed` | `checked in production: passed` |
| `end-check-failed` | `check failed: bug filed` |
| deadline answered `drop` | `deadline reached: dropped` |
| deadline answered `bug` | `deadline reached: bug filed` |

## On the desktop

The board's `flow.stage` stays `call-post-deploy-check` throughout; the
desktop (`issues.jsx`, `postDeployState`) reads `flow.step`, the innermost
activity: `merged, awaiting deploy` (the deadline set, the poll, the deploy
wait), `verifying in production` (the check's Smith run), `verifying, next
check scheduled` (the waits between checks) and `waiting on you: the deploy
deadline` (the question). The bar splits the post-deploy segment: awaiting the
deploy in its first half, checking in its second; only the Smith run moves
it. The doctor's positions tell the same waits apart (`TicketPosition`'s
scheduled waits). The timeline draws each deploy as a line and each check run
as a card with its outcome.
