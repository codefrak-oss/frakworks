# Summarize: a live run summarized

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

The endpoint was the Spy endpoint (`/spy`, #423) until #881. The desktop's
**Summarize** button on the running coding card asks the engine what that
Smith is up to. The engine answers with two short texts written by an LLM:
`summary`, the whole session so far in at most 20 words, and `now`, fewer than
15 words on what it is doing at this moment, answered from the session's most
recent turns. Both are high level: the prompt tells the
model never to list file names, paths, functions or single edits.

**The session is stored as it runs.** `SmithTelemetry`'s follow of the
sandbox's stdout ("Transcripts and the live-run gauge") tees each line to
`SmithSessions`, which writes it from a virtual thread of the run's own, in
batches, to `engine.smith_session_line` (`run_id`, `seq`, `read_at`, `line`),
under one `engine.smith_session` row per run (`repo`, `issue`, `mode`, `model`,
`started_at`, and `ended_at` once the follow ends). A line over 64 KiB is
stored cut, with a marker; a full queue (10 000 lines) drops lines; a database
error is logged once per streak and never fails the run. A run followed again
after an engine restart reopens its row and numbers its lines on from the
stored ones. It is the stdout tee rather than a Claude Code hook because the
engine already reads that stream for every run, whatever the image, and a hook
would have needed a write under the sandbox's `.claude/` and a way back into
the engine from inside it. **There is no retention yet**: rows stay until
someone deletes them.

**The endpoint.** `POST /ticket/{owner}/{repo}/{n}/summarize` (`summarize/SummarizeController.kt`)
finds the live run on that ticket (the newest one `SmithTelemetry` follows),
condenses its stored lines into a digest (`SmithSessions.digest`: what the
Smith said, each tool it called with the call's description or command, the
result, and log lines; tool output is left out; over 24 000 characters it keeps
the head and the tail), takes its last 3 entries whole (`SmithSessions.recent`:
each entry one assistant message, its text and tool calls), and asks the
model. The request's user turn holds the digest, then the last entries under
a heading of their own, "The most recent turns:", and the prompt tells the
model to answer `now` from that section only. It writes nothing.

| Status | When |
|---|---|
| 200 | `{"runId", "mode", "model", "summary", "now"}`; the word limits (20 and 14) are enforced on what the model answered, cut with an ellipsis. |
| 403 | The caller is a Smith sandbox: a Smith may not summarize a run. |
| 404 | The repo is not a target repo. |
| 409 | No Smith is running on the ticket, or it has written nothing yet. |
| 502 | The provider failed or did not answer the JSON asked for. |
| 503 | No enabled `anthropic` account has an API key (the message names `PUT /accounts/{name}/api-key` and the desktop's Edit… > API keys), or the sandbox lookup failed. |

**The model and the account.** One Anthropic `/v1/messages` call
(`summarize/HttpSessionSummarizer.kt`) to `engine.summarize.model`
(`ENGINE_SUMMARIZE_MODEL`, default `claude-haiku-4-5`) at `engine.anthropic.upstream`,
with a 15 s timeout (under the desktop's 20 s). It sends an `anthropic`
account's API key (`engine.ai_account.api_key`, "AI accounts") on `x-api-key`,
never the account's Claude Code OAuth token: `AiAccounts.peekApiKey` reads the
enabled account with a key, least recently used first, without touching
`last_used_at`, so a summary does not move the Smiths' rotation. With no such
key it answers 503 and calls nothing. Each answered call goes in the
consumption ledger as caller `summarizer`, naming the account; its spend counts
toward the account's API credit used.
