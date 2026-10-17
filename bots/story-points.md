# Story points

Every ticket the intake files carries a **story-point estimate**: its
relative size, on the Fibonacci scale **1, 2, 3, 5, 8, 13**, and for a
feature **21, 34, 55**. The refining
Smith (`smith.md`, "Intake tickets", Outcome A) chooses it, in the same run
that judges the kind and tier; there is no other pointing step and no model
call of its own. It is the ticket's `points` in the refine result, and the
engine writes it into the trailer (`· points <n>`), records it in
`engine.handled_issue.points` and returns it as `points` on
`GET /ticket/{owner}/{repo}/{n}` (`engine/README.md`, "Intake", `points`).
The desktop shows it beside the ticket's labels.

Points are recorded and shown, nothing more: they do not schedule, pick a
tier or order the queue. A proposal's builds and a hand-filed issue carry
none. A split feature's children do: the design step points each child by
this file (1 to 13; a child is not split again), from the child's work alone,
and the engine records them as it does an intake-filed ticket's. The
children's total is expected to differ from the parent's estimate; the gap is
the signal, not something to force to match.

## The scale

Size is effort plus uncertainty, read from the ask and the repo, not from the
model that will do it. A tier says *how capable* a run must be
(`model-choice.md`); points say *how much* there is. A one-line fix to
subtle code can be tier 7 and 1 point; a wide mechanical rename tier 2 and 5.

| Points | Anchor |
|---|---|
| **1** | A one-liner, a doc or config tweak; nothing to find out. |
| **2** | One small change in one place, with its test; the way is clear. |
| **3** | A few files in one area (a field through a class, its test, its doc). |
| **5** | A change across layers or areas (engine and desktop, a migration and an API), or one area with real unknowns. |
| **8** | A cross-cutting feature or a design with open questions; several stages. |
| **13** | Big, several stages and real unknowns; the most a chore or a bug takes. |
| **21** | A feature in three or four parts across the engine and the desktop (a new event channel replacing polls). Features only. |
| **34** | A new subsystem: its own API, storage, runtime and UI, five or more parts (custom jobs). Features only. |
| **55** | Changes how the whole fleet works, across every layer and many parts. Features only; rare. |

Between two values, take the larger. 21, 34 and 55 are a feature's, because
its design step splits it into children; an ask bigger than 13
that you would file as a chore or a bug is a feature, so file it as one, at
its real size. Do not shrink a big feature to fit: the engine refuses points
above 13 on anything else, and nothing above 55.

## Reference tickets

Real completed frakworks tickets that sit squarely at each value. Compare the
ask with them by the work it describes: how many places it touches, and what
is still unknown. They are frakworks issues; a Smith in another repo reads
the descriptions, not the code.

| Points | Ticket | The work |
|---|---|---|
| **1** | #1150 | Rewrite one passage of `smith.md`; docs only. |
| **1** | #671 | Show each repo's GitHub URL as a link on its desktop tile. |
| **2** | #791 | Remove a duplicate status badge in one desktop view, with its test. |
| **2** | #1154 | Fix one regex in one test file, with two unit tests. |
| **2** | #1075 | Hide a step card's cost line until it is known: one component, its test, a README line. |
| **3** | #1156 | Carry one field through `IntakeRefs` into the brief, with its tests and docs. |
| **3** | #1015 | Add `offset` paging to one MCP tool, with tests and docs. |
| **3** | #808 | A Copy-conversation button on First mate: a pure function, the button, tests. |
| **5** | #519 | A `repo` parameter for the intake through the engine, the MCP tool and the desktop. |
| **5** | #528 | Move a setting into the database: migration, GET/PUT API, desktop section. |
| **5** | #536 | A new MCP tool with two actions and its own resolution logic. |
| **8** | #965 | An async GitHub-setup check: engine endpoint, caching, coalescing, SSE events, desktop rework. |
| **8** | #502 | Three Jev decisions in the intake (kind, mode, tier), each with a fallback. |
| **8** | #674 | Browse each target repo's files in the desktop through two new cached engine endpoints. |
| **13** | #398 | The proposal flow: a new BPMN, decision verbs, access rules, filing builds. |
| **13** | #876 | Friction notes: an MCP tool, a table, the brief, a retro turn, a diagnostics tool. |
| **21** | #955 | Server-sent events replacing the desktop's polls: an engine stream, its event types, each view moved over (split in four). |
| **34** | #1212 | Custom jobs: an SDK, three kinds of job, plugin loading, a registrar and API, desktop and MCP tools (split in four, then more). |

A 2 changes one thing in one place; a 3 carries a change through a few files
in one area. When an ask could be either, find the second place it must
touch: none is a 2.

## The user's value binds

A `Points: <n>` line in the ask (or an answer) binds, as a `Tier:` line does:
file that value as is and list `points` in `userNamed`. A value off the scale
is a question (Outcome C), not a rounding.
