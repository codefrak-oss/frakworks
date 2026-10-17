---
name: test-intake
description: File a test intake through the engine with a fixed, harmless, unambiguous ask that should take the `file` path, not `ask`. Use when the user says "test intake", "file a test intake ticket", "run a test intake" or similar.
---

Files the fixed ask below with `submit_ask` on the engine's `frakworks` MCP
server (CLAUDE.md, "Filing tickets"). Real GitHub writes: an intake issue and
a ticket on this checkout's repo.

**The tier is 1 (haiku low).** The user set it for test intakes on
2026-09-23, so it counts as named; pass `tier: 1` and do not ask.

**The ask**, verbatim; only `<stamp>` changes, to the current UTC time as
`YYYY-MM-DDTHH:MMZ`, so each run's ticket is distinct:

```
Test intake <stamp>. Append exactly one line to the end of engine/README.md:
`<!-- test intake <stamp>: safe to delete -->`
Change nothing else in the file or the repo. Done when the last line of
engine/README.md is that comment and the diff is that one added line.
```

Why it looks like this: the refining Smith takes the `ask` outcome when it
would have to guess what was meant. One file, one exact line, and a checkable
done condition leave nothing to guess. An HTML comment is invisible when the
README renders, so the ticket is real work that changes nothing a reader sees.

**Post it** with `submit_ask({ask, tier: 1, repo})`, `repo` from
`gh repo view --json nameWithOwner`, no `context`. Say first that it files a
real intake issue and a real ticket on this checkout's repo. The ticket
should be filed as a `chore`: if the user may authorize chores on that repo,
that chore starts at once and, if it passes its reviews, **merges the
one-line change into the repo**; say that too. Otherwise the ticket waits for
an `authorized` label. Reply with one line saying it was submitted, then
stop. Close whatever is left open when the test is done with.
