# Run identity tokens: the contract

A Smith run carries a token a downstream MCP server can verify itself, so the server can put the run into HITLP
`attested.onBehalfOf` instead of only repeating the `X-Frakworks-Run` header the run asserts (#2453; engine/README.md,
"Run identity tokens"). The engine mints it (`RunTokens`, engine/modules/smith); nothing outside the engine holds a key.

<svg xmlns="http://www.w3.org/2000/svg" width="640" height="170" font-family="sans-serif" font-size="12">
  <rect x="10" y="20" width="160" height="60" rx="6" fill="#eef" stroke="#447"/>
  <text x="90" y="45" text-anchor="middle">engine</text><text x="90" y="62" text-anchor="middle">RunTokens (sealed key)</text>
  <rect x="240" y="20" width="160" height="60" rx="6" fill="#efe" stroke="#474"/>
  <text x="320" y="45" text-anchor="middle">Smith run</text><text x="320" y="62" text-anchor="middle">SMITH_MCP_SERVERS</text>
  <rect x="470" y="20" width="160" height="60" rx="6" fill="#fee" stroke="#744"/>
  <text x="550" y="45" text-anchor="middle">MCP server</text><text x="550" y="62" text-anchor="middle">(e.g. ops-server)</text>
  <line x1="170" y1="50" x2="240" y2="50" stroke="#333" marker-end="url(#a)"/>
  <line x1="400" y1="50" x2="470" y2="50" stroke="#333" marker-end="url(#a)"/>
  <path d="M550 80 V130 H90 V80" fill="none" stroke="#333" stroke-dasharray="4 3" marker-end="url(#a)"/>
  <text x="205" y="42" text-anchor="middle">mint per server</text>
  <text x="435" y="42" text-anchor="middle">X-Frakworks-Run-Token</text>
  <text x="320" y="150" text-anchor="middle">GET /.well-known/jwks.json · POST /run-tokens/introspect (no engine token)</text>
  <defs><marker id="a" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z"/></marker></defs>
</svg>

## Delivery

Every `http`/`sse` MCP server a Smith run gets from its repo (`smith`/`both` rows of `engine.repo_mcp_server`) receives
the header `X-Frakworks-Run-Token: <jwt>`, one token per server, minted at run start with `aud` set to that server's
URL. It travels only inside `SMITH_MCP_SERVERS` (the run's env Secret on Kubernetes, the container env on Docker): never
the brief, the prompt or the Job spec. A row header of that name is dropped. A `stdio` server gets none.

## The token

A JWS compact ES256 token. Header: `{"alg": "ES256", "typ": "frakworks-run+jwt", "kid": "<RFC 7638 thumbprint>"}`.

| claim | value |
|---|---|
| `iss` | `frakworks-engine` |
| `sub` | `run:<run id>` |
| `jti` | the run id (the `X-Frakworks-Run` value) |
| `aud` | the one MCP server URL the token was minted for, exactly as the repo's row has it |
| `repo` | the run's repo, `owner/name` |
| `issue` | the ticket number; `null` for a custom-job run |
| `mode` | `work`, `refine`, `review`, ... |
| `scope` | `frakworks.run.attest`: it attests who the run is, and grants nothing |
| `iat`, `exp` | seconds since the epoch; `exp` is `iat` plus the run's longest life (`engine.smith.k8s.active-deadline`, 24 h by default) |

## Verifying

1. Split the token; the header's `alg` is `ES256` and `typ` is `frakworks-run+jwt`, else reject.
2. Find the `kid` in the engine's `GET <engine>/.well-known/jwks.json` (cache it; refetch on an unknown `kid`).
3. Check the ES256 signature (raw `r||s`, 64 bytes) over `<header>.<claims>`.
4. `iss` is `frakworks-engine`, `scope` is `frakworks.run.attest`, `sub` is `run:<jti>`.
5. `aud` is exactly your own URL: a token minted for another server, and so for another repo's, fails here.
6. `exp` is in the future.
7. Before attesting (putting the run in `attested.onBehalfOf`), `POST <engine>/run-tokens/introspect` with
   `{"token": "...", "audience": "<your URL>"}`. The answer is RFC 7662's: `{"active": false}`, or `active: true` with
   the claims. It is active only while steps 1-6 pass, the run's session is open and the run was not revoked. Steps 1-6
   alone give expiry, not revocation.

Both endpoints need no engine bearer token. The reference verifier is `docs/run-identity-token/verify.py` (standard
library only), tested by `test_verify.py` (`python3 docs/run-identity-token/test_verify.py`).

## Revocation

A run's tokens answer inactive at introspection as soon as its `engine.smith_session` row has `ended_at` (the sandbox
exited or its result was collected), and for a run a login revokes with `POST /run-tokens/{runId}/revoke`. A server that
only verifies offline accepts the token until `exp`.

## What it cannot do

The token is no engine credential. Offered as `Authorization: Bearer`, the engine answers 401 (`a run identity token is
not an engine token`): it cannot call the engine's API, answer a HITLP question or approve a HITLP request. A
downstream server must likewise never treat it as an approver's identity, only as the requester's.

## Keys and rotation

Keys are P-256 pairs in `engine.run_token_key`, the private half sealed with `ENGINE_SECRETS_KEY` (the `kid` as
associated data). The newest unretired key signs; one is made on first use. `POST /run-tokens/rotate` (a login's, not a
Smith's) retires it and makes a new one, answering `{kid}`. A retired key stays in the JWKS for one token lifetime, so
every token it signed still verifies until it expires; rotation deletes keys retired longer ago. Without
`ENGINE_SECRETS_KEY` no token is minted: the run's servers get no `X-Frakworks-Run-Token` and one WARN line says so.
