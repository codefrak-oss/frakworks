# Bug

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

`bpmn/bug.bpmn` (process `bug`) is the flow for a ticket of kind `bug`,
started like a chore by a `bug` label with `authorized` (by a trusted login). It is the chore's loop with evidence on either side: why the bug
happens before anything is changed, and whether it is gone after the merge.

    reproduce → reproduced or understood? ─→ fix it → pr → engine check → security review → review → up to date → merge → verify on main → fixed or unverified? ─→ close ticket
                    │                          (the chore's loop, delegates shared)                                                              │
                    └─ not reproduced → label needs-repro, leave open                                                      still broken → report, leave open

- `reproduce` is a Smith run in `reproduce` mode on the default branch. It
  commits nothing and writes `ReproduceResult`, one of:
  - `reproduced`: a `repro` (a command or exact steps) shows it now.
  - `understood`: it cannot happen in the sandbox (a phone, a car, another
    app taking the audio, a race), but the report, the logs and the code
    agree on a cause; `evidence` says which, and how confident.
  - `not-reproduced`: no repro and no cause it trusts; a `summary` of what
    it tried.
  - `ask`.

  `reproduced` and `understood` name the `rootCause` and say whether a
  regression test can catch it (`testable`, else `untestableWhy`), and both
  go on to the fix. The engine keeps the result as `reproduction`, and every
  later brief carries it.

  Its scratch files are thrown away, except under `/work/out/repro/`: the
  one place for the files its repro needs (scripts, fixtures, fetched
  binaries), which the `repro` cites as `/work/in/repro/<path>`, the path
  they have in the later runs. On `reproduced` or `understood`, `collect
  result` reads that directory out of the sandbox as one tar
  (`SmithSandbox.outputDir`) and keeps it as `reproFiles`; an absent or
  empty one leaves no variable. `start smith` for `fix it` and `verify on
  main` unpacks it to `/work/in/repro/` (`repro/<path>` in the inputs tar),
  owned by the smith user with each file's mode kept, so a fetched
  executable stays executable. The cap is 32 MiB of tar
  (`REPRO_MAX_BYTES`): past it the run is still collected, without the
  files, and the reproduction carries `filesDropped` saying so, which the
  verify and fix prompts tell the Smith to rebuild from.
- `fix it` is the chore's work run, told the root cause. A testable bug gets
  a regression test; an untestable one gets a line in the summary saying
  why, and the correctness review accepts a sound reason instead of a test.
  PR, the engine check, security review, review, bringing the PR up to date
  and merge are the chore's, in the chore's order (a failed check or a
  security finding goes back to `fix it`), with the same delegates and the
  same merge-conflict handling ("Bringing the PR up to date", under
  "Chore"): a branch behind main gets the merge pushed, checked,
  security-reviewed and reviewed again, a conflict
  goes back to `fix it` with a `rebase-<n>-1` finding, at most 2 consecutive
  unresolved conflict rounds (a resolved round resets the count; lifetime cap 6), and GitHub's 405 at `merge pr` comes back to the step.
- `verify on main` is a Smith run in `verify` mode on the default branch
  after the merge. It writes `VerifyResult`: `fixed`, `still-broken`, or
  `unverified` (nothing it can run would show it). `close ticket` closes both
  `fixed` and `unverified`, and says which: "Fixed, but not verified: …" is
  stated, not left for anyone to follow up.
- `label needs repro, leave open` (`${markNotReproduced}`) comments what was
  tried and labels the ticket `needs-repro`; nothing was changed. Failing to
  reproduce in a sandbox is not evidence the bug is gone, so it stays open.
  To run it again, add detail to the body, then remove and re-add
  `authorized` (the run changed nothing, so nothing stands in its way; see
  "The scan").
- `report still broken` (`${reportStillBroken}`) comments the summary and
  leaves the ticket open. The flow ends there: another round would start
  from a PR that has already merged.
- Both `ask` outcomes, the reproduce run's and the work run's, post the
  question and wait as the chore's does, each at its own `answer the smith's
  question`; the answer runs that same step again (`reproduce` or `fix it`),
  with every answer so far in its brief. Answering needs a trusted login.

A bug filed by an intake starts at once like a chore when the intake's
authorizer is a trusted login ("Authorization from an intake");
otherwise it waits for its `authorized` label.

## After the merge: the post-deploy check

When the PR has a `## POST-DEPLOYMENT-CHECK` section, the bug flow does not end at its merge: it calls
`post-deploy-check` (`call-post-deploy-check`), which waits for the merge to reach production, runs check
Smiths until one passes or fails, asks the user at the deadline, and closes the ticket. See
[post-deploy-check.md](post-deploy-check.md).
