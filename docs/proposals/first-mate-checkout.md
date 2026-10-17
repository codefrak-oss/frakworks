# First mate checkout without waiting for the clone

Ticket #100113. Until then every First mate session pod cloned its repo in an
init container before claude started (#100112). The options weighed, and the
user's pick.

| Option | Start latency | Freshness | Disk | Git token | New infra |
|---|---|---|---|---|---|
| A. Clone at start (the old way) | blocks for a clone | exact | per pod | init container only | none |
| **B. Background sidecar** | ~0; code a clone later | exact | per pod | sidecar only | none |
| C. `--filter=blob:none` partial clone | fast | exact | small | later blob fetches need it: breaks for private repos | none |
| D. Shared bare mirror on a PVC, CronJob refresh | ~1 s (`--reference`) | lags the cron | shared | CronJob | PVC, Job (a stop condition) |
| **E. First mate asks via MCP (`checkout_repo`)** | 0 until asked | exact | only if used | sidecar only | none |
| F. Engine-hosted read-only git mirror | ~1-3 s | fetch on serve | shared on the engine PVC | engine only | engine route and auth |

- **A** is simple but every chat waits for a clone, even one that never reads code.
- **C** would hand claude's container fetches that need the token it must never have.
- **D** needs new storage and a token-holding job, both stop conditions of the ticket.
- **F** removes the token from the pod entirely and is the best fit at scale; it
  is the biggest change (engine endpoint, auth, mirror disk) and can follow as
  its own ticket.

## Picked: B + E

The user picked B and E together. The `clone` container is a native sidecar
(restartable init container) that alone holds `FM_GIT_TOKEN`, clones in the
background and marks `.done`/`.failed`; the relay starts claude at once, copies
the clone into `/work/repo` when it is done (a resumed checkout with a `.git` is
kept) and tells First mate by system note. The `checkout_repo` tool, served by
`fm-relay --mcp-checkout` inside the pod, reports pending/ready/failed, waits
for a pending clone and retries a failed one through a `.request` marker the
sidecar watches. No new secret, storage, host or permission. Behaviour:
`engine/docs/first-mate-sessions.md`, "Checkout".
