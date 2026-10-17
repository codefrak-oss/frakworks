# Predict a review: Jev on the live review run

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

The desktop's review tile shows, while a review Smith runs on a ticket, a bar
from -100 (deny) to +100 (approve): Jev's call on what the reviewer will decide
(#1746). The desktop asks back-to-back while the ticket view is open
(`observability/desktop/README.md`, "Review prediction"). Every prediction Jev made on a
ticket is read with the diagnostics server's `read_review_predictions` ("The diagnostics server").

**The endpoint.** `POST /ticket/{owner}/{repo}/{n}/predict-review`
(`summarize/PredictReviewController.kt`) finds the ticket's live review run: the
newest `engine.smith_session` row for that repo and issue with `mode = 'review'`
and `ended_at` unset (`SmithSessions.running` with a mode). The state is the
run's digest (`SmithSessions.digest`, as for summarize: assistant text and tool
calls, tool output left out, 24 000 characters at most) with the ticket's
`owner/name#n` and its title (read from GitHub; left out when that fails). No
summarizer and no other model call: one `JevDecider.decide`, use case
`review-prediction`, one `noul` question, "Will this review run end with outcome
`approved` (1) rather than `changes` (0)?", its instructions naming the review
result's two outcomes (`bots/smith.md`, the `review` result). Each call is one
`engine.jev_call` row, so `GET /jev/summary` reports it.

**The answer.** `verdict` is `approve` at a `noul` of 0.5 or above, else
`deny`; `confidence` is Jev's confidence in 0..1 (when the answer carries none,
the noul's distance toward its side, `max(noul, 1 - noul)`); `value` is
`round(confidence * 100)`, negated for `deny`. `lines` counts the
`smith_session_line` rows the digest was built from.

**`fresh`.** The engine keeps the last prediction per run in memory, with its
`lines`. A call that finds no new lines answers it again with `fresh: false`
and makes no Jev call; one that finds new lines asks Jev and answers `fresh:
true`. So the desktop's back-to-back chain costs one Jev call per reviewer
line, not one per request. Each replica keeps its own: with two, a run's first
call on each asks Jev once.

| Status | When |
|---|---|
| 200 | `{"runId", "callId", "verdict", "confidence", "value", "lines", "fresh"}`; `verdict`, `confidence` and `value` null when Jev gave no answer. |
| 403 | The caller is a Smith sandbox. |
| 404 | The repo is not a target repo. |
| 409 | No review run is live on the ticket. |
| 502 | Jev failed: no answer within its attempts, or an error status. |
| 503 | No usable Jev account, or the sandbox lookup failed. |
