# Research

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

`bpmn/research.bpmn`, for a ticket of kind `research`: a question answered
with an engine-kept page, acked by the asker, with follow-up rounds (decided
with the user 2026-10-06, #1262). It starts like every flow ("Starting a flow
from an issue"): a `research` label plus `authorized` from someone with
`research` access on the repo. An intake refined as `research` waits for its
own `authorized`: `research` is not in `FROM_INTAKE` ("Authorization from an
intake"). Deciding its questions and its ack (on the HITLP server)
takes the same `research` access.

```
research filed
  → answer the question (smith run, mode research; outcome ask → post the smith's question → answer → back to the run)
  → keep the answer (engine.research_answer, the round, a comment "Answer, round N: <summary>")
  → ask for the ack (an ask in GET /needs) → ack the answer (HITLP decision record) → read the ack (jev research-decision)
      accept → close completed
      save[: <dir>] → save to the repo (one engine commit on the default branch) → close completed; a refused path → ask for the ack again
      drop → close not planned
      anything else → the follow-up: back to the run as round N+1, with the thread
```

- **The mode.** One `reusable-smith-run` in mode `research`, at the ticket's
  tier, on the default branch: the sandbox of a `design` run (the clone, the
  engine over `$ENGINE_URL`, the MCP servers). The Smith answers the question in
  the ticket, cites what it read (file, README section, endpoint, row counts
  with the instant read), changes no file and cuts no bundle (`research` is
  not in `COMMITTING_MODES`; `readResult` refuses a result that left one). Its
  "Stop and ask if" conditions go through the question path (`ask`,
  `${postFeatureQuestion}`). On round 2 and later the brief's `research` field
  carries every earlier round (`round`, `summary`, `md`) and its `answers` the
  follow-ups, and the page is rewritten whole.
- **The result** (`smith/schemas/research.schema.json`, "What each mode
  writes"): `answered` with `answer.md` (the question, the answer, the
  citations, the date) and `answer.html` (one self-contained page by
  `smith/pitch-guide.md`, passing `PitchRules`), or `ask`.
- **The store.** `keep the answer` upserts `engine.research_answer`
  (`full_name`, `issue`, `round`, `process_instance`, `summary`, `md`, `html`,
  `answered_at`; "Its own tables"), a table rather than a process variable so
  the page outlives the flow's 180-day history. `GET
  /ticket/{owner}/{repo}/{n}/pitch` serves the latest round as `{kind: "html",
  path: null, prUrl: "", content, round}`, also after the ticket closes.
  Nothing is pushed; the answer sits in git only after `save`.
- **The ack.** `ask for the ack` records the question (a `decision` row in
  `GET /needs`, `wanted` `answer ready: accept, save, drop, or ask a
  follow-up`) and comments the summary, the round and the verbs. `read the
  ack` puts every answer to Jev, use case `research-decision`, a `choice` over
  `accept`, `save`, `drop` and `follow-up` (`AckVerb.read`): a written verb is
  never overridden; a pick at `engine.jev.decision-floor` or above is taken;
  below it, with no answer, or when the call fails, the rule reads it, and the
  rule's fallback is `follow-up`, never a re-ask. The `engine.jev_call` row
  records the action and the outcome.
  - `accept[: <why>]`: comment, close completed.
  - `save[: <dir>]`: one commit by the engine's own identity on the default
    branch (`GitPush.commitFiles`, the engine's one write outside `smith/`
    branches), message `Research #<n>: <title>`, adding
    `<dir>/<yyyy-mm-dd>-<slug>.md` and `.html` (`dir` default `bots/research/`,
    must be under `bots/research/` or `docs/`, no `..`); the commit is
    commented, then the ticket closes completed. A path elsewhere is refused
    with a comment and the ack is asked again, saying why first.
  - `drop[: <why>]`: comment, close not planned.
  - anything else is the follow-up: round N+1 runs the Smith again with the
    thread. No cap on rounds.

The run's exit, incidents, the doctor (`Doctor.TICKET_FLOWS`), `GET
/ticket/.../runs`, the transcript and friction notes work as for a chore; the
board's `flow` shows the stage (`StageEstimates`: `call-answer`, role
`research`, 15 min by default). The desktop draws the page under "Answer, round
N" with the ack controls while the decision waits
(observability/desktop/README.md, "The Answer section").
