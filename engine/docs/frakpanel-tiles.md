# frakpanel tiles

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

The engine can host tiles for a frakpanel (the Corsair Xeneon Edge panel,
[codefrak-oss/frakpanel](https://github.com/codefrak-oss/frakpanel); its
`TILES.md` is the contract): 812x720 dark, touch-first pages with no cursor
(`?cursor=1` shows one) and no frame-blocking header, which the panel loads in
a slot by URL. Most installations have no panel, so it is off by default.
A slot cannot send a header, so the tile pages and their `state` polls need
no bearer token ("Tokens", the filter): they answer only the tile DTOs, which
carry no credential, author or instance id, and 404 while the setting is off.
Each tile's `state` carries the engine's `commit` (as `GET /version` answers
it); a page remembers the one from its first poll and reloads itself when a
later poll answers another, so an open tile picks up a newly deployed engine.

- **The setting:** row `frakpanel.tiles` of `engine.engine_setting`, `false`
  as V52 seeds it. `GET /frakpanel/tiles/enabled` answers `{"enabled": bool}`;
  `PUT /frakpanel/tiles/enabled` with `{"enabled": true}` or `false` sets it
  (anything else is a 400) and publishes a `setting` event. The desktop's
  Config > frakworks > frakpanel tiles is the same switch, and shows both tile
  URLs while on.
- **While off,** every tile URL below answers 404.
- **AI accounts tile:** `GET /frakpanel/tiles/ai-accounts` is the page; it
  polls `GET /frakpanel/tiles/ai-accounts/state` (relative, so it works direct
  or behind a relay) every 30 s. One row per AI account: its name, provider,
  state (`enabled`, `disabled`, or `blocked`: enabled but a window is rejected
  with its reset ahead, as the picker sees it) and its 5-hour and 7-day plan
  usage with the time to reset, then its Fable meter (`fable`: the `7d_oi`
  pair off the last `claude-fable-*` reply, `no reading` without one) and,
  while the Fable limit is reached, a red "Fable limit reached" line with the
  countdown (`fableLimit`: `reached` and the recorded reset), from the same rows as Analytics > AI Accounts
  ("AI accounts", "The usage meter"). A 5-hour, 7-day or Fable window whose
  reset is more than 5 minutes past (the desktop's `STALE_RESET_MS`,
  `observability/desktop/src/renderer/accounts.jsx`) reads as a stale reading
  rather than the old one: no utilization number, an empty bar, and the
  countdown "awaiting new reading" — the window has already rolled over. The
  "Fable limit reached" line follows no such rule (the desktop applies none
  to it either). No tier-to-account section, and never a
  credential or an email note: the state is its own DTO.
- **frakworks issues tile:** `GET /frakpanel/tiles/frakworks-issues` is the
  page; it polls `GET /frakpanel/tiles/frakworks-issues/state` (relative) every
  30 s. The state is one `IssueBoard.board()` read per poll, the same as
  `GET /issues` with no `repo` ("The issues board"): `at`, the board's
  `failures`, and `issues`, one row per open issue of every target repo with
  `repo`, `number`, `title`, `url`, `labels`, `points`, `kind`, `createdAt`,
  `updatedAt` and `state` {`stage`, `outcome`, `ended`, `incident` (boolean),
  `pendingQuestion` (boolean)}. Nothing else of the board row (author, who
  asked, instance ids, PR URLs) reaches the panel: the state is its own DTO.
  The page groups the rows by repo under its `owner/name`, as the desktop's
  Engine > Issues does in All repos mode, and shows `#n`, the title, labels,
  points, the state in the desktop's `STAGE_PHRASES` words (a copy the desktop's
  `frakpanel-tiles.test.js` holds equal to `issues.jsx`'s), an incident or
  question mark, and the age; rows scroll by touch, nothing is clickable.

`local_tiles.json` entries on the panel host, when it reaches the engine:

```json
{"url": "http://<engine-host>:8090/frakpanel/tiles/ai-accounts", "title": "AI accounts"}
{"url": "http://<engine-host>:8090/frakpanel/tiles/frakworks-issues", "title": "frakworks issues"}
```

`8090` is the default host-published port; use the installation's. A panel
host that cannot reach the engine directly can use frakpanel's
`edgerelay.py --target <engine-host>:8090` and a `relay://<name>/frakpanel/tiles/ai-accounts`
slot URL instead (frakpanel README, the slot URL table).
