# First mate screenshots the desktop app (#100105): the design

First mate asks for a screenshot of the user's desktop app (the whole window,
or the left Explorer, the middle work area or the right First mate pane) with
the `screenshot_desktop` tool on `/mcp/frakworks`. The engine is the middle
man; the desktop takes the picture only when its "Let First mate screenshot
this app" setting is on. The wire details are in
[`engine/docs/desktop-channel.md`](../engine/docs/desktop-channel.md).

<svg xmlns="http://www.w3.org/2000/svg" width="720" height="250" viewBox="0 0 720 250" font-family="sans-serif" font-size="12">
  <rect x="10" y="20" width="200" height="200" rx="8" fill="#eef3fb" stroke="#4a6fa5"/>
  <text x="110" y="40" text-anchor="middle" font-weight="bold">Desktop app (main)</text>
  <text x="110" y="62" text-anchor="middle">setting: on / off (userData)</text>
  <text x="110" y="82" text-anchor="middle">capturePage(rect of pane)</text>
  <rect x="25" y="150" width="170" height="50" rx="6" fill="#fff" stroke="#4a6fa5"/>
  <text x="110" y="172" text-anchor="middle">First mate (local hosting)</text>
  <text x="110" y="188" text-anchor="middle">child process</text>
  <rect x="300" y="20" width="200" height="200" rx="8" fill="#f3f8ee" stroke="#5a8a3a"/>
  <text x="400" y="40" text-anchor="middle" font-weight="bold">Engine</text>
  <text x="400" y="62" text-anchor="middle">DesktopChannel (per login)</text>
  <text x="400" y="82" text-anchor="middle">/mcp/frakworks</text>
  <text x="400" y="100" text-anchor="middle">screenshot_desktop</text>
  <text x="400" y="118" text-anchor="middle">(First mate only)</text>
  <rect x="580" y="120" width="130" height="80" rx="8" fill="#fbf1ee" stroke="#a55a4a"/>
  <text x="645" y="150" text-anchor="middle">First mate pod</text>
  <text x="645" y="168" text-anchor="middle">(Kubernetes hosting)</text>
  <line x1="210" y1="60" x2="300" y2="60" stroke="#333" marker-end="url(#a)"/>
  <text x="255" y="54" text-anchor="middle" font-size="10">GET /desktop/channel</text>
  <line x1="300" y1="90" x2="210" y2="90" stroke="#333" stroke-dasharray="4" marker-end="url(#a)"/>
  <text x="255" y="104" text-anchor="middle" font-size="10">capture frame (SSE)</text>
  <line x1="210" y1="125" x2="300" y2="125" stroke="#333" marker-end="url(#a)"/>
  <text x="255" y="140" text-anchor="middle" font-size="10">POST /desktop/capture</text>
  <line x1="195" y1="180" x2="300" y2="180" stroke="#333" marker-end="url(#a)"/>
  <text x="250" y="196" text-anchor="middle" font-size="10">tools/call</text>
  <line x1="580" y1="160" x2="500" y2="160" stroke="#333" marker-end="url(#a)"/>
  <text x="540" y="154" text-anchor="middle" font-size="10">tools/call</text>
  <defs><marker id="a" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="#333"/></marker></defs>
</svg>

## Network

- **One connection of its own, outbound.** The desktop opens
  `GET /desktop/channel` with its sign-in bearer and holds it (SSE), apart from
  `GET /events`: the event stream is per repo, has no reply path and is
  reopened whenever the repo selection moves; a capture is about the machine.
  No inbound port and no new secret: the desktop works behind NAT as the event
  stream does.
- **Request and answer.** The engine sends `{type: capture, requestId, pane}` on
  the stream; the desktop answers `POST /desktop/capture/{requestId}`. The tool
  call waits up to **15 s**, streaming progress as `await_children` does.
- **Refusals.** No desktop connected: at once. Setting off: at once, from the
  state the desktop reported on connect and on every change; no capture frame
  is sent. No answer: after 15 s.

## Several desktops for one login

The desktop focused most recently answers (each window focus is reported with
`POST /desktop/channel/{id}/state {focused: true}`); with none focused since
it connected, the newest connection. Captures go to that one desktop only.

## First mate in a Kubernetes pod

The call lands on the engine as from local hosting and routes by the session's
login: it works when that login's desktop is connected with the setting on,
and is refused the same way otherwise. The registry is in memory per replica:
with more than one replica serving `/mcp` and `/desktop`, a desktop on another
replica is not seen (the engine runs one leader replica today).

## Smiths

`screenshot_desktop` is listed for First mate's role (`McpCaller.Nobody`) only
and needs a proven login; a Smith (`McpCaller.Smith`) does not see it and its
call is refused naming its run. The `/desktop/*` endpoints refuse a Smith by
source address. No shared server (`hitlp`, a repo's `both`-audience server)
carries it.

## The image

A PNG of the window or the pane's rect (`capturePage(rect)`, the rect from the
pane's `getBoundingClientRect` in the renderer: `nav.explorer`, `main.work`,
`aside.firstMate`). The cap is `first-mate-images.js`' 5 MiB; a PNG over it is
re-encoded as JPEG at quality 90, 75, then 60, with no downscaling, so the text
on screen stays legible. The image is handed to the model in the tool result
only: the engine keeps nothing at rest.

## The setting

"Let First mate screenshot this app" in Config > frakworks, the desktop's, per
machine: `<userData>/screenshot-setting.json`, off when missing, persistent
once set. The web version has none (it is not the desktop's window).
