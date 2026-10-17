# Tokens

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

A non-Smith caller proves which login it acts as with a bearer token the engine
issued ("Tokens"). A Smith never has one: it is known by its source address, and an
`Authorization` header from a Smith's address is ignored.

- **Shape.** `frk_` and 32 bytes from `SecureRandom` as base64url without
  padding, 43 characters (`EngineTokens`). The engine stores the token's hex
  SHA-256 and its first 8 characters after the prefix in `engine.engine_token`
  ("Its own tables"), never the token: a database read, a log line or a
  `toString` cannot yield one. The prefix is what the log names a token by.
- **Endpoints.** `POST /tokens {login, name, expiresInDays?, purpose?}` mints one for a
  login on the trusted list ("Starting a flow from an issue"; 400 naming a login
  that is not: trust it first, then mint) and answers `{id, token, login, name,
  prefix, expiresAt, purpose}`, the one time `token` appears; `purpose` is
  absent or `first-mate`, which labels the token as the phone's for revocation
  scoping ("First-mate sessions"): the first-mate session API takes a plain
  sign-in bearer (`purpose` absent) or a `first-mate` token, so the desktop
  mints no token of its own; `GET /tokens` lists every row without hashes,
  `purpose` included; `DELETE /tokens/{id}` revokes, and ends the first-mate
  sessions started with that token whatever its purpose (no other token's),
  deleting their pods and revoking their pod tokens. A `first-mate-pod` token is never minted
  through `POST /tokens`: the engine mints one per session pod, with
  `engine_token.session_id` naming the session, and revokes it with the pod
  (`EngineTokens.revokeForSession`); `BearerTokenFilter` lets it reach only
  `/first-mate/pod/{its session}/`, and every other path is 403 `a
  first-mate-pod token reaches only /first-mate/pod/{its session}/`. `GET /whoami` answers
  `{login, tokenId, name}` for the token presented, 401 without a valid one. All
  of `/tokens` is 403 from a Smith sandbox. First mate has them as
  `read_tokens`, `mint_token` and `revoke_token` ("The frakworks server").
- **The filter.** `BearerTokenFilter` runs on every request except `GET
  /health`, `GET /ready`, `POST /hooks/github`, the frakpanel tile reads
  (the GETs of each tile's page and its `state`, "frakpanel tiles"; not
  `/frakpanel/tiles/enabled`), reads included. The web version's static
  files (`/web/`) are not the engine's: the `frakworks-web` image serves
  them (`deploy/k8s/README.md`). It asks the
  sandbox provider first: a Smith is its run, its `Authorization` header is
  ignored and it needs no token (the provider down: 503). Anyone else needs
  `Authorization: Bearer <token>`: the engine hashes it and finds the row, and
  none, unknown, revoked or expired is 401 `{"error": "no such token"}` (logged
  with the prefix and the path); a login no longer trusted is 401 `@<login> is
  not a trusted login`. On `POST /mcp/{server}` the refusal is JSON-RPC error
  `-32001` with the same texts and the request's id, for every method. A valid
  token puts the login and the token's id on the request, where
  `McpCaller.Nobody.login`, `Actor.Login` and every controller's actor are taken,
  and bumps `last_used_at` at most once a minute (one conditional UPDATE,
  replica-safe).
- **No other way to name a login.** No login header is read anywhere (the old one
  is gone), and `by` is not a field of `POST
  /intake/{id}/attachments`, `DELETE /intake/{id}`, `POST
  /camunda/incidents/{id}/retry`, `.../fix` and `.../propose`, `POST
  /ticket/{owner}/{repo}/{n}/amend`, `POST /access` and `DELETE /access/{login}`: one
  that a body or query still carries is 400 `by is not a field: the bearer token
  names the login`. `submittedBy` on `POST /intake` from a non-Smith is ignored;
  the submitter is the token's login. Jobs, the refactor config, a repo's MCP
  servers, private docs and `callerPermission` on `GET /repos` take the token's
  login too. So do the friction
  corrections (`/friction/...`) and `PATCH /doctor/sightings/{id}`, which
  refuse a `by` the same way. `POST /intake/notifications/{id}/delivered`
  still takes its own `by`.
- **The bootstrap door.** While `engine.engine_token` has no unrevoked row,
  `POST /tokens` (and the two others) from a non-Smith caller needs no token:
  the first token is minted through it, recorded as created by the minted login
  and logged at WARN (`first token minted through the bootstrap door for @<login>
  from <address>`). Once an unrevoked row exists, the three endpoints need a
  bearer token like any other write (401 `a bearer token is needed ...`). Locked
  out (every token lost or revoked)? Revoke the rest in the database, `UPDATE
  engine.engine_token SET revoked_at = now() WHERE revoked_at IS NULL` over
  `psql`, and the door is open again for one mint.
- **Rotation and revocation.** Mint the new token (`mint_token`, or `POST
  /tokens` with the old one), put it where the caller reads it (1Password; the
  desktop's config in part 2), then revoke the old by id: the next request with
  it is 401. A revoked row stays listed with its `revoked_at`. Removing a login
  from the trusted list (`remove_access`) leaves its tokens in place but every
  request with one is 403 until the login is trusted again; revoke them too when
  the person is gone. An expiry is optional (`expiresInDays`, 1 to 3650); an
  expired token answers as a revoked one, and `GET /tokens` shows `expiresAt`.
- **Claude Code and the desktop (part 2, #2206).** The desktop signs in on
  Config > frakworks > Sign in to the engine (observability/desktop/README.md,
  "Sign in to the engine"): main keeps the token safeStorage-encrypted and sends
  `Authorization: Bearer <token>` on every engine call, the event stream
  included, and writes it into First mate's `--mcp-config` beside the login
  header. A hand-written Claude Code config carries it the same way. Claude
  Code expands `${VAR}` in an `.mcp.json` server's `headers`, so the file holds
  no secret:

  ```json
  {
    "mcpServers": {
      "frakworks": {
        "type": "http", "url": "http://localhost:8090/mcp/frakworks",
        "headers": {"Authorization": "Bearer ${FRAKWORKS_TOKEN}"}
      },
      "frakworks-engine-diagnostics": {
        "type": "http", "url": "http://localhost:8090/mcp/frakworks-engine-diagnostics",
        "headers": {"Authorization": "Bearer ${FRAKWORKS_TOKEN}"}
      }
    }
  }
  ```

  `FRAKWORKS_TOKEN` comes from the shell that starts Claude Code: from
  1Password, `export FRAKWORKS_TOKEN=$(op read 'op://<vault>/frakworks engine token/credential')`,
  or from the gitignored `.env` (`FRAKWORKS_TOKEN=frk_...`, sourced before
  `claude` starts). The `curl` examples in this README carry the same header,
  `-H "Authorization: Bearer $FRAKWORKS_TOKEN"`, from the same variable; a
  `docker exec <smith-container>` example stays bare, since a Smith is known by
  its address and its header is ignored.
