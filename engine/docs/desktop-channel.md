# The desktop channel and screenshot_desktop (#100105)

First mate can see the user's desktop app: the `screenshot_desktop` tool on
`/mcp/frakworks` answers a PNG of the app's window, or of one pane. The engine
is the middle man, because First mate's call always lands on the engine
(local hosting: a child of the desktop with its sign-in bearer; Kubernetes
hosting: a session pod), and a pod cannot reach the desktop. The desktop
reaches the engine outbound only: no inbound port, no new secret. Design and
its reasons: [`docs/first-mate-screenshot.md`](../../docs/first-mate-screenshot.md).

## The connection the desktop holds

While signed in, the desktop's main process (`observability/desktop/src/desktop-channel.js`)
holds `GET /desktop/channel?allowed=<true|false>` open with its sign-in
bearer: a `text/event-stream` of `data:` frames, JSON, with a `: ping` every
15 s when idle. `allowed` is the desktop's setting ("Let First mate
screenshot this app") at connect.

| frame | meaning |
|---|---|
| `{"type":"hello","id":"<desktop id>"}` | first; the id names this connection in the state endpoint |
| `{"type":"capture","requestId":"<uuid>","pane":null\|"left"\|"middle"\|"right"}` | take a capture and answer it |

The desktop answers on two endpoints, with the same bearer:

- `POST /desktop/capture/{requestId}` `{image: <base64>, mimeType: "image/png"|"image/jpeg"}` or `{error}`.
  404 for a request that is not the login's or no longer waits; 400 for a bad
  image (not base64, another type, over 5 MiB, the cap of `FirstMateImages`).
- `POST /desktop/channel/{id}/state` `{allowed?, focused?}`: the setting moved,
  or the window gained focus. 404 for another login's or a closed connection.

All three refuse a Smith sandbox by source address (403) and need a bearer
(401). The registry (`DesktopChannel`, engine-firstmate) is in memory, per
replica, and keeps no image: a capture is handed to the tool call and dropped.

## The tool

`screenshot_desktop {pane?}` is First mate's only: it is in `tools/list` for
`McpCaller.Nobody`, and a call needs the login a bearer proved. A Smith does not
list it, and its call is refused naming its run. The call streams (as
`await_children` does) while it waits.

- **Which desktop.** The login's desktop focused most recently; with none
  focused since it connected, the newest connection.
- **Refusals**, each an `isError` result with `{error}`:
  no desktop of the login connected (at once); the setting off (at once, no
  capture frame is sent); the desktop refused (its pane not on screen, its
  own setting off); the desktop disconnected; no answer within 15 s.
- **Answer**: `{pane, mimeType, bytes}` and an image content block. A PNG,
  re-encoded as JPEG (quality 90, 75, 60) only when the PNG is over 5 MiB.
  The image reaches the model only; the engine stores it nowhere.

With First mate in a Kubernetes pod the session's login routes the call the
same way: it works when that login's desktop is connected with the setting on.
A desktop connected to another replica than the one serving the call is not
seen (the registry is per replica).
