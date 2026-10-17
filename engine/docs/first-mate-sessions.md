# First-mate sessions

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

The iPhone app's chats with a first mate (module `engine-firstmate`). Each session runs its first mate in a pod of its
own (below); with no cluster to ask, the store, API and config work on their
own and no pod starts.

- **The phone's token.** Mint it with `POST /tokens {"login": "<login>", "name":
  "iPhone", "purpose": "first-mate"}` ("Tokens"). `/first-mate/sessions` takes
  a signed-in login's own token (a plain sign-in bearer, `purpose` absent) or a
  `first-mate` token: none is 401, any other 403 (a session pod's
  `first-mate-pod` token never drives a session). `purpose: first-mate` remains
  the phone's revocation-scoping label: revoking a token (`DELETE /tokens/{id}`,
  or the `revoke_token` tool) ends the sessions started with that token,
  whatever its purpose, and no other token's, deletes their pods and revokes
  their pod tokens, and unregisters the push devices it registered ("Push
  notifications").
- **The desktop's token.** The desktop and the web page drive First mate with
  the sign-in bearer that represents the user, and mint no token of their own:
  a session's row keeps the token id of whichever bearer started it, so
  revoking the sign-in token ends its sessions exactly as revoking a phone's
  ends the phone's. Nothing else is relaxed: `/first-mate/sessions` is still
  401 without a token and 403 with a `first-mate-pod` one.
- **Where the desktop's First mate runs.** Row `firstmate.hosting` of
  `engine.engine_setting` (`FirstMateHosting`), `local` (a child process of
  the desktop app) or `kubernetes` (a session pod of this engine, as the
  phone's). No migration seeds it: with no row the engine answers `local`, so
  an upgrade changes nothing. `GET /settings/first-mate-hosting` answers
  `{"hosting": "local"|"kubernetes"}` and `PUT {"hosting": …}` sets it and
  answers the same; any other key, a non-string or another value is a 400
  naming `hosting`. `kubernetes` is a 400 saying why while `smith.hosting` is
  not `kubernetes` or one of its Kubernetes settings is blank (`namespace`,
  `image`, `engineUrl`: a First mate pod runs on them, "Hosting models");
  `local` is always accepted. A successful PUT publishes the `setting` event
  `{"name": "firstmate.hosting", "value"}`. The desktop reads it; nothing in
  the engine branches on it.
- **Endpoints.** `POST /first-mate/sessions {repo, ask?, tier?}` starts a session on a target
  repo (400 for another, naming the target repos) and answers `{id, login, repo,
  state, createdAt, endedAt, tier, dropped, firstLine}`. `tier` is optional, a
  whole number in the engine's tier map ("Tier resolution"; 1-10, else a 400
  naming `tier`): it is kept on the session (`engine.first_mate_session.tier`,
  V87) and its pod runs at it on every start, a resume included; absent (the
  phone sends none; null on the row) the pod runs at `engine.firstmate.tier`. `POST
  /first-mate/sessions/{id}/messages {text, clientId?, images?}` appends the phone's message to the
  transcript (409 once ended) and publishes a `first-mate` frame with `line`
  `{"type":"user","text"}`; a repeat with the same `clientId` (a retried post)
  stores nothing and answers 200 with the first row. `images` is optional, a
  list of `{media_type, data}` (`data` the base64 of the bytes) under the
  desktop's allowlist (`observability/desktop/src/first-mate-images.js`,
  `FirstMateImages` here with the same wording): `image/png`, `image/jpeg`,
  `image/gif` or `image/webp`, whose first bytes match the type, at most 5 MiB
  decoded each and at most 5 per message, else a 400. The row's body keeps
  them (`{text, images}`; `{text}` with none) and the inbox hands them to the
  pod; the published frame carries the `text` and an `images` count, never
  the data. `GET /first-mate/sessions/{id}/transcript`
  answers the session and its `messages` (`{seq, role, body, at}`, oldest
  first); `DELETE /first-mate/sessions/{id}` ends it (204; 200 when it already
  was). Every route takes a plain sign-in bearer or a `first-mate` token
  (above): 401 without one, 403 with a `first-mate-pod` token. A session is
  its owner's: any other login gets 404 for it.
- **Tables.** `engine.first_mate_session` (id, login, token_id, repo, state
  `open`/`ended`, created_at, ended_at) and `engine.first_mate_message`
  (session_id, seq, role, body JSON, at), V75, which also adds
  `engine.engine_token.purpose`; V76 adds state `frozen` and
  `engine.first_mate_snapshot` (below); V87 adds the nullable `tier`.
- **The pod's MCP config.** `FirstMateMcpConfig` builds it: the engine's
  `frakworks`, `frakworks-engine-diagnostics` and `hitlp` at
  `<engine>/mcp/<name>` with `Authorization: Bearer <the session's token>`,
  plus the repo's First mate-audience servers (`first-mate` and `both` rows, never
  a `smith` one; "First mate's MCP servers") read in-process, secrets
  opened. A stdio server whose command is not in the image, and an http/sse
  server on a loopback or local host (127.0.0.0/8, `::1`, `localhost`,
  `*.local`), are dropped; their names are the chat's first line (`firstLine`,
  also the transcript's first `system` message). The secret carries the engine's
  servers at the pod's in-cluster engine (`FM_ENGINE_URL`), not the caller's
  address: a phone comes in through the Ingress, a private address the pod cannot
  reach.
- **The pod's egress to LAN servers.** The `firstmate-sandbox` NetworkPolicy
  blocks every private range, so a repo server on the LAN (`http://desk.example.com:7781/mcp`)
  would never connect. At each pod start `FirstMateEgress` resolves every http/sse
  repo server's host, and for each private IPv4 address (10/8, 172.16/12,
  192.168/16, 169.254/16, 100.64/10) opens that address and the URL's TCP port
  for that pod alone: a NetworkPolicy `first-mate-<session id>` selecting the pod
  by its session label, so no Smith or other session's pod gets the hole.
  Policies add up, so the sandbox itself is unchanged. The engine logs what it
  opened; `stop` deletes the policy with the pod and Secret. The address is
  resolved at pod start: if the host moves, the next pod (a new chat or a
  resume) picks it up. Anyone who can add a repo's MCP server can so point a
  first mate at a LAN address and port.
- **The session pod.** `POST /first-mate/sessions` creates a Secret and a
  long-lived Pod, both `first-mate-<session id>`, in namespace
  `frakworks-firstmate` under ServiceAccount `first-mate` (deploy/k8s/base),
  never in `frakworks-smith` (`FirstMatePodSpec`, `KubernetesFirstMateSessionPods`).
  The pod runs the Smith image (`smith.k8s.image`) sandboxed as a Smith's Job is:
  non-root, every capability dropped, the runtime's seccomp profile,
  PriorityClass `frakworks-smith`, and the same limits (`engine.smith.memory-bytes`,
  `engine.smith.cpus`) and requests (`smith.k8s.memory-request-bytes`,
  `smith.k8s.cpu-request`). Its one container runs `fm-relay -- claude -p --input-format
  stream-json --output-format stream-json --verbose --model <m> --effort <e>
  --allowedTools … --disallowedTools … --strict-mcp-config --mcp-config
  /etc/firstmate/mcp.json --append-system-prompt <prompt>` (stdin closed: the
  engine never attaches): the
  prompt and tool lists are `shared/first-mate-prompt.json`, the desktop's own;
  model, effort and account are the session's `tier` when it has one, else
  tier `engine.firstmate.tier` (8, the desktop First mate's default), resolved
  at every pod start (a resume too) and the account picked as a Smith's is
  (`AiAccounts.pick`). The Secret holds `mcp.json` (mounted read-only at
  `/etc/firstmate`), the account's token (an env var from the Secret) and the
  pod's own `first-mate-pod` token (key `fm-token`, the relay's `FM_TOKEN`), so
  none is in the pod's spec. Each pod start bumps the session's `pod_epoch`
  and mints that token for the session (`EngineTokens.mint`, purpose
  `first-mate-pod`, `session_id` the session's); deleting the pod revokes it
  (`revokeForSession`). The cluster is reached as the Smith sandbox
  reaches it (the engine's ServiceAccount in a pod, else the kubeconfig).
- **The relay.** `fm-relay` (`engine/smith/fm-relay.py`, `/usr/local/bin/fm-relay`
  in the Smith image, Python standard library only) is the container's entry
  point: it runs `claude` as its child and connects out to the pod relay
  protocol below with `FM_ENGINE_URL` (the hosting settings' engine URL),
  `FM_SESSION_ID` and `FM_TOKEN`. It learns `delivered_seq` (an ack of 0),
  long-polls the inbox after it, writes each message to claude's stdin as one
  stream-json user line (`{"type":"user","message":{"role":"user","content":
  <text>}}`; for a row with `images`, `content` is one
  `{"type":"image","source":{"type":"base64","media_type","data"}}` block per
  image and then the `{"type":"text","text"}` block, the desktop's
  `buildContent` shape) and then acks its seq, retrying the ack before it polls again.
  Every stdout line is numbered `out_seq` and appended to the spool
  (`FM_SPOOL`, `/var/spool/fm-relay`, an emptyDir) before it is posted in
  batches (50 lines or 200 ms) until the engine answers `{accepted}`; while the
  engine is unreachable lines wait on disk, and a relay restart in the pod
  continues `out_seq` from the spool. It posts `status running` once claude
  starts and `status exited` with claude's code when it ends. The engine keeps
  nothing about the pod in memory: whichever replica serves a call stores the
  row and publishes the `first-mate` frame. `DELETE /first-mate/sessions/{id}`
  (and a token revoke) deletes pod and Secret and revokes the pod's token.
  `engine/smith/tests/test_fm_relay.py` runs a fake claude behind the relay
  against a fake engine stopped and restarted mid-session.
- **Freeze.** `FirstMateFreezer` runs every minute: an open session whose last
  transcript row is 15 minutes old or more is frozen. It first marks the
  session `freezing`, so the inbox answers `{"freeze": true}`; the relay closes
  claude's stdin, flushes its spool, posts `status exited` and stays up. The
  freezer waits up to 30 s for that row (with none, it reads the pod as it is),
  then reads the pod with
  `exec`: the working folder's size, the Claude session file and a tar.gz of
  the folder, keeps them in `engine.first_mate_snapshot`, marks the session
  `frozen` and deletes pod and Secret (revoking the pod's token). A phone
  message that comes while the session is freezing stays queued after
  `delivered_seq` and reaches the resumed pod. A folder over 200 MB is not kept
  (`workdir_dropped`); the session file still is. Every pod works in
  `/work/repo` (an emptyDir, the container's `workingDir`), so the session
  file is always `~/.claude/projects/-work-repo/<claude id>.jsonl` (Claude's
  projects folder for a cwd: every character but a letter or digit becomes
  `-`).
- **Checkout.** claude never waits for the clone (#100113, design:
  `docs/proposals/first-mate-checkout.md`). The pod's `clone` container is a
  native sidecar (an init container with `restartPolicy: Always`, `fm-relay
  --clone-sidecar /work/clone`, small requests, the pod's limits): it clones
  the session's repo (`FM_REPO`) default branch (`--depth 50`, from GitHub
  over the pod's public egress) into `/work/clone/repo` in the background,
  then writes `/work/clone/.done`, or `.failed` with git's error, and stays
  up watching for `/work/clone/.request` to clone again. It alone gets
  `FM_GIT_TOKEN`, when the repo has a Smith token, from the session's Secret
  (key `git-token`; `TargetRepos.smithToken`, a Smith's `GH_TOKEN`): the main
  container has no such env and no mount of the key, so claude can never
  read the token, not even from the relay's `/proc/<pid>/environ`. The token
  reaches git through `git-askpass` alone, never a URL, so it is in no
  `.git/config` and no snapshot tar. After any resume restore, a
  `/work/repo` that already holds a `.git` is kept as it was and nothing more
  happens. Otherwise (a new session, a resume whose folder was dropped
  (`workdir_dropped`), or one whose snapshot held no checkout) the relay
  copies the clone in before claude starts when it is already done; when it
  is pending, claude starts at once with a note at the end of its appended
  prompt that the code is being cloned, and the relay's watcher copies the
  clone into `/work/repo` once `.done` appears and writes claude a system
  note that the code is ready. A failed clone does not stop the session: the
  failure (with git's error) is in the appended prompt when it is known at
  start, else written to claude as a system note. Into a folder that already
  has files only the clone's `.git` is copied in and the tracked files not
  already there are written.
- **`checkout_repo`.** Every pod's `--mcp-config` has a `checkout` stdio
  server (`fm-relay --mcp-checkout`, in the main container; it holds no
  token) with one tool, `checkout_repo` (allowed in
  `shared/first-mate-prompt.json`): it answers `ready`, `pending` or
  `failed: <error>`, waits up to `wait` seconds (at most 300) for a pending
  clone, and with `retry: true` after a failure writes `.request` so the
  sidecar clones again. A repo server named `checkout` is dropped.
- **Resume.** A message to a frozen session reopens it and starts a new pod
  with `FM_RESUME` set: its relay `GET`s the snapshot, writes the session file
  back to the same path, unpacks the folder into `/work/repo`, runs `claude …
  --resume <claude id>`, posts `status running` and reads the inbox after
  `delivered_seq`, where the frozen session left off. Until the new pod's
  first outbox line (or the pod-start timeout) a
  `first-mate` frame with `seq` 0 and `line` `{"type":"resuming"}` goes out
  every 2 seconds. When the folder was dropped, a `system` row says so before
  the message.
- **Keeping frozen sessions.** A frozen session is kept until it is deleted
  (`DELETE`, or a token revoke, ends it and drops its snapshot). `GET`/`PUT
  /settings/first-mate-expiry {"days": <n or null>}` sets an optional expiry,
  row `firstmate.frozen-expiry-days` of `engine.engine_setting`, absent (off)
  by default: with it, sessions frozen longer ago are deleted with their
  transcript and snapshot. V76 adds the columns (`claude_session_id`, `cwd`,
  `last_message_at`, `frozen_at`, `workdir_dropped`) and the snapshot table.
- **The pod relay protocol** (`FirstMatePodController`): a session pod pulls the phone's messages and
  pushes claude's output over HTTP, so any replica serves it. All under
  `/first-mate/pod/{id}/`, and only with the session's own `first-mate-pod`
  token (401 without a token, 403 with any other):
  - `GET inbox?after=<n>&wait=<s>` answers the `user` rows with seq > n, oldest
    first; with none it long-polls up to `wait` seconds (at most 25), woken by
    the session's `first-mate` frame (the message post publishes one; the event
    relay carries it to every replica) and re-reading the database every 2 s as
    a backstop (`FirstMateInbox`); `[]` on timeout, `{"freeze": true}` while the
    session is `freezing`.
  - `POST inbox/ack {seq}` records the highest message written to claude:
    `delivered_seq = GREATEST(delivered_seq, seq)`, never lowered; answers
    `{deliveredSeq}`.
  - `POST outbox {from, lines}` stores each line (at most 500, strings: parsed
    JSON, or the raw text) as an `assistant` row numbered `out_seq` from `from`
    in the session's current `pod_epoch`; a line whose `out_seq` is already
    stored is skipped, so a retried or overlapping batch adds only what is new.
    Each new row publishes its `first-mate` frame (and an init line records the
    Claude session id). Answers `{accepted}`, the highest `out_seq` stored in
    the epoch. `pod_epoch` goes up by one at each pod start
    (`FirstMateSessions.nextEpoch`), so a new pod's `out_seq` 1 never collides.
  - `POST status {state: starting|running|exited, code?}` appends a `system`
    row `{"type":"pod-status", state, code}` and publishes its frame.
  - `GET snapshot` answers `{claudeSessionId, cwd, deliveredSeq, sessionFile,
    workdirTar, size, dropped}` (the files base64), 404 without a snapshot.

  A row's `seq` is `MAX(seq) + 1`; when another replica took it first (a
  primary-key conflict) the insert retries at the next one. V77 adds
  `delivered_seq` and `pod_epoch` to `first_mate_session`, `pod_epoch`,
  `out_seq` and `client_id` to `first_mate_message` with unique indexes on
  `(session_id, pod_epoch, out_seq)` and `(session_id, client_id)`, and
  `session_id` to `engine_token`.
