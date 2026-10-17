# Proposal

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

`bpmn/proposal.bpmn`, for a ticket of kind `proposal`: whether to build
something, and what, decided as a doc under `docs/proposals/` before anyone
builds it (decided with the user 2026-09-25).
It starts like every flow ("Starting a flow from an issue"): a `proposal`
label plus `authorized` from someone with `proposal` access on the repo, or an
intake whose refiner chose the kind ("Authorization from an intake").
Deciding its questions and its decision (on the HITLP server) takes
the same `proposal` access on the repo, not a separate intake access: a token
login without it gets 403.

**Picking an option** (#1720, #2209). A pitch that lays out options declares them in
the propose result (`options: [{id, label}]`) and marks each in the page with
`data-option-id` and a disabled `data-choose` placeholder button
(`smith/pitch-guide.md`, checked by `PitchRules`, and in the sandbox by
`check-result propose`, its port). A propose result the rules refuse reruns
the draft once, with a new Smith told why, before it parks ("Incidents", "A
rejected work result"). The page stays as
sandboxed as before (no script, no same-origin), so it cannot act: the
desktop's Pitch view reads `options` and `awaitingChoice` from `GET
/ticket/.../pitch` and draws the live buttons outside the frame. At the propose Smith's
question, a click on an option decides its HITLP task with `option <id>: <label>`. At the decision, the options are a
selectable set (selecting sends nothing) and two buttons name the selection:
**Build with option X** is recorded as
`accept and build auto` (the doc merges, the build ticket is filed in auto mode
and started), and **Record option X only** is recorded
as `accept` (the doc merges, the ticket closes, nothing is built). The decision
rides along as structured data, the `decisionChoice` and `decisionChoiceWhy`
variables set with the answer, which `task-read-decision` takes as given (the
why `option <id> (<label>) chosen`): no `DecisionVerb` parse and no Jev call.
Typed decisions go through the decision record, the verb rules and Jev. Repo HTML gains no
power: it can only name ids the engine already holds.

The draft is carried as the `bundle` variable and pushed only once a review
approves it. Every propose run after the first gets that latest draft as
`/work/in/commits.bundle` and checks it out on `smith/issue-<n>`, on top of the
PR branch (or the default branch before the first push), so a redraft after
review findings, `revise:`, a rebase conflict or an answered question starts
from the draft the brief describes. Its own bundle is cut from that start, so it
carries the previous round's unpushed commits plus its new ones, and `open/update
PR` pushes it as a fast-forward. A draft that no longer contains the PR branch
(the branch moved under it) stops the run (exit 78) rather than drop either side.
Before the first push there is no PR branch to lose: when the default branch
moved since the draft was cut, the draft's own commits are replayed onto it, and
only a replay conflict stops the run (exit 78, naming the conflict).
`run-smith.sh` exits 78 (`EX_CONFIG`) on a missing input or a brief its mode
cannot run on, and 69 (`EX_UNAVAILABLE`) when a step before `claude` fails (the
`git clone`, after a `smith: git clone of <repo> failed (<git's code>)` line).

```
(draft)   draft the proposal ─ask─→ post the question → answer it → (draft)
              │ drafted
              ▼
          review the draft ─changes (findings)─→ (draft)
              │ approved
              ▼
          open/update PR
              ▼
(decide)  ask for the decision → decide on the proposal → read the decision
                                                           ├─ not understood ──────→ (decide)
                                                           ├─ revise: <text> ──────→ (draft)
                                                           ├─ accept and build [auto] → file the build ─┐
                                                           └─ accept / shelve / reject ─────────────────┤
                                                                                                        ▼
                                                                                              log the decision
                                                                                                        │
          ┌─────────────────────────────────────────────────────────────────────────────────────────────┘
          ▼
(update)  bring PR up to date ─┬─ up to date ─────────────────────────────┐
                               ├─ clean → push the catch-up (no review) ──┤
                               └─ conflict (a rebase finding) → (draft)   ▼
                                                                      merge PR ─merge conflict─→ (update)
                                                                          ▼
                                                     build filed? ─yes─→ start the build ─┐
                                                          └─no────────────────────────────┴→ close ticket
```

`(name)` marks a step that a later arrow goes back to. There is no security
review anywhere in the flow (below).

- `draft the proposal` is a `propose` Smith on `smith/issue-<n>`: it creates
  or rewrites the doc and commits, never pushes ("What each mode writes").
  On every draft it also writes or rewrites the doc's pitch,
  `docs/proposals/<slug>.html`: one self-contained page (inline CSS and SVG,
  nothing remote, a viewport meta tag, a dark-scheme rule) with a diagram of
  the mechanism, the alternatives as a comparison table, the open questions,
  the outline and the cost, by `smith/pitch-guide.md` (in the image at
  `/etc/smith/pitch-guide.md`). The `.md` is the record and the pitch must
  agree with it; `readResult` lints the page (`PitchRules`).
  `ask`, or a draft with an important question, goes to `post the smith's
  question` (`${postFeatureQuestion}`) and `answer the smith's question`,
  decided on the HITLP server by someone with `proposal`
  access; the answers bind the next draft.
- `review the draft` is the ordinary `review` mode on the **unpushed** draft
  (`/work/in/commits.bundle` and the brief's `proposal`), with a proposal
  checklist: claims about current mechanics cite a file or README section;
  at least one real alternative, with why not; the open questions listed; a
  cost (tiers, runs); the pitch agrees with the doc; on a revisit, what
  changed since the last decision.
  Findings go back to the draft, and nothing was pushed. There is no security
  review: `readResult` keeps the bundle to `docs/proposals/`.
- `open/update pr` (`${openPr}`) pushes the approved draft and opens the PR
  `Proposal: <ticket title>`, its body the draft's summary; a later round
  comments `Round <n>, redrafted: <summary>`.
- `ask for the decision` (`${postDecision}`) records the question (a
  `decision` in `GET /needs`) and comments it on the ticket with the PR, a
  link to the pitch on the PR's branch
  (`https://github.com/<repo>/blob/<branch>/<pitch>`; none for a draft from
  before pitches), the summary and the verbs; the desktop renders the same
  page on the ticket through `GET /ticket/{owner}/{repo}/{n}/pitch`.
  `decide on the proposal` waits for the answer, which
  `read the decision` (`${readDecision}`) reads with Jev (below); the verbs as
  the rule (`DecisionVerb.parse`) reads them, ignoring case and leading
  punctuation:
  - `accept[: <why>]`: log, merge, close;
  - `accept and build[: <why>]`, or `accept and build auto`: the same, and
    `file the build ticket` (`${fileProposalBuild}`) files a `feature` titled
    as the proposal, labelled `feature:confirm` (or `feature:auto`), whose
    body starts `Builds: docs/proposals/<slug>.md` and whose trailer is
    `<!-- engine: proposal #<n> · tier <t> · p3 -->`, the tier being the
    proposal's own, left out when it had none (then the build starts at the
    default, "Tier resolution");
  - `revise: <text>`: draft again, the text in the brief's `answers`; no cap
    on rounds, each is the user's call;
  - `shelve: <why>` or `reject: <why>`: log with that status, merge, close;
    the doc lands on the default branch either way, so it is greppable;
  - anything else, or a verb missing its required text, asks again, quoting
    the answer that was not understood.

  **Jev reads the decision.** Every answer is put to Jev ("Jev accounts"),
  use case `proposal-decision`: a `choice` over `accept`, `accept and build`,
  `accept and build auto`, `revise`, `shelve` and `reject`, each described
  as the question describes it, the state being the answer and the
  proposal's summary. A pick at confidence 0.8 or more
  (`engine.jev.decision-floor`) is taken; `accept and build` and `accept and
  build auto` need 0.95 (`engine.jev.decision-build-floor`), since they file
  a feature and start work. The why (for `revise`, the text to redraft from)
  is the answer minus leading punctuation and a leading verb, by string
  handling (`DecisionVerb.why`), not by Jev; a `revise`, `shelve` or `reject`
  with nothing left asks again. So "Please redo section 2" revises and "Park
  this for now" shelves, each with the answer as its text. A verb the user
  wrote as the rule reads it is never overridden: when Jev picks another,
  the written verb stands. **The fallback:** with no Jev answer (no jev
  account, a timeout, 429/529 past the retries, any error) or a pick below
  its floor, the rule reads the answer exactly as before Jev, and an answer
  it cannot read is asked again. The call's `engine.jev_call` row has the
  pick and confidence (in `response`), the floor applied (`threshold`: the
  build floor for a build pick), and the `action` (`took <verb>`, `below
  floor <f>, rule: <verb>`, `kept the verb as written: <verb>`, `re-asked:
  <verb> with no text`, `no answer, rule: <verb>`). Its `outcome` is the
  verb as written when the rule reads one (`verb as written: <verb>[ (jev
  picked <other>)]`), the free label for Jev's pick; for an answer that was
  asked again, it is how the next answer was read (`the answer to the
  re-ask read as <verb>: @<by>: <text>`).
- `log the decision` (`${logDecision}`) is one commit by the engine on the
  PR's branch (`GitPush.commitEdit`, fast-forward): it sets the doc's
  `Status:` (`accepted`, `shelved`, `rejected`) and appends
  `- <yyyy-mm-dd> <status> (#<n>)[ — <why>][; build filed as #<m>]` to its
  `Decision log:`, adding either line under the title when missing. A line
  already there is not added again, so a retried step, or a second
  decision on the same day after a conflict round, changes nothing.
- `bring pr up to date` is the chore's step ("Bringing the PR up to date"):
  up to date goes to the merge; a clean merge of the default branch is
  pushed as a catch-up (`push the catch-up`, `${openPr}`) and merged with no
  second review, since the branch is docs only and the merge is the
  engine's; a conflict goes back to the draft as a `rebase-<n>-1` finding,
  and the redraft is reviewed and decided again (at most two conflict
  rounds).
- `merge pr`, then for a build `start the build` (`${startProposalBuild}`):
  when the user who decided has `feature` access on the repo, the build
  ticket starts as an intake's would (`AuthorizedStart`: label event 0,
  `authorized by @x via proposal #n`); otherwise it says on the build ticket
  that it waits for `authorized`. `close ticket` comments the merge and
  `Decided: <status>[ — <why>][; build filed as #m]`, and closes the ticket.

The PR's squash title is the PR's, `Proposal: <title>`. The build ticket's
`Builds:` line, and the `Status: built` its PR sets, are the feature's
("Feature", "`Builds:`").

The desktop's Proposals branch (#660) lists the docs through `GET /proposals`
(each doc's `Status:`, `Decision log:`, `Ticket:` and the build its log filed,
parsed from the default branch) next to the board's open `proposal` flows, and
draws a doc with no ticket through `GET /proposals/{owner}/{repo}/{slug}/page`;
it decides on the HITLP server with the same verbs as above.
