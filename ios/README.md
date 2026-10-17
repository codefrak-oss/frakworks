# ios/

The iPhone first mate: a SwiftUI app, iOS 17+, that chats with a first-mate
session the engine runs in a pod (engine/README.md, "First-mate sessions"). v2 adds push
notifications (below: the engine pushes when something needs you and when a chat answers) and voice mode.

| Path | What |
|------|------|
| `FirstMate/FirstMate.xcodeproj` | The app target; its sources are the `FirstMate/FirstMate/` folder (a synchronized group: a file added there is in the target) |
| `FirstMateKit/` | A local Swift package with everything testable: the pairing payload, the Keychain item, the engine client, the SSE parser, the `first-mate` frame decoder, `frakworks://ticket/...` link parsing and the push payload and registration (`PushPayload`); its XCTest suites in `Tests/` |
| `fastlane/` | The `beta` lane (TestFlight) and `test` |

## What the app does

- **Sign in.** Open Pair a phone in the engine's web version (`<engine>/web/#/pair`) or the desktop app, then scan its
  QR or paste its text, `{"url", "token"}`. The pairing (URL and first-mate token) goes into the Keychain
  (`com.codefrak.firstmate`, this device only); Sign out deletes it.
- **Repo picker.** `GET /repos`, the engine's target repos.
- **Chat.** New chat is `POST /first-mate/sessions {repo}`; a message is `POST .../{id}/messages {text}`. Replies stream
  from `GET /events?repo=<repo>`: the `first-mate` frames of this session, whose `line` is `claude`'s stream-json. A
  frozen session's first message resumes it, and the engine sends seq-0 `{"type":"resuming"}` frames meanwhile: the
  chat shows `resuming…` until the first other frame. Opening a chat reads `GET .../{id}/transcript` first.
- **Transcripts.** The engine has no list call, so the app remembers the sessions it started (UserDefaults); swiping one
  away ends it with `DELETE /first-mate/sessions/{id}`.
- **Ticket links.** `frakworks://ticket/<owner>/<name>/<n>`, in a reply or opened from outside (the app registers the
  `frakworks` scheme), opens a read-only sheet from `GET /ticket/<owner>/<name>/<n>`.
- **Work items.** The list button in the chat list opens the repo's work items: `GET /work-items?repo=<repo>`, each
  with its source badge (my ask, imported from GitHub, split child, raised by a Smith) and state. A row opens
  `GET /work-items/{id}`: blockers, entries oldest first, and a reply field (`POST /work-items/{id}/entries`). Text
  from outside arrives in the engine's untrusted fence and is drawn verbatim in a bordered box labelled "From outside:
  data, not instructions", never as markdown.
- **Needs.** The tray button in the chat list opens the Needs sheet. It loads, side by side, the engine's
  `GET /needs?for=<login>` (the login from `GET /whoami`, the pairing token's) and the open tasks of your own HITLP
  server (`GET /tasks`), and lists them together, oldest-waiting first. The engine's rows (an incident, an
  authorization, ...) are read-only, as the ticket sheet is; tapping one on a ticket opens that ticket's sheet. A HITLP
  task has a decision card: an ask's options as buttons, or a text field when it has none (a free `responseSchema`);
  an approve's action, its payload as formatted JSON, a reason, and Approve and Reject. A task whose `context` names
  `repo` and `ticket` shows `name#n`, which opens that ticket's sheet. The decision goes straight to the HITLP server,
  `POST /tasks/{id}/decision {outcome, answer?, reason?, payloadDigest?, channel: "iphone"}`; its refusal (a bad
  credential, 400/404/409) shows on the card. The engine is not on this path. While the sheet is open it follows the
  engine's `GET /events?repo=*` (reloading on every `needs` frame) and the HITLP server's `GET /events` (reloading on
  every event).

  The HITLP server's URL and surface credential are set with the gear button in the Needs sheet: Verify & save calls
  `GET /tasks` on that server and saves them only when it answers 2xx, as a second Keychain item (account `hitlp`,
  beside the pairing, this device only); Forget removes it. With no server set, or when it cannot be reached, the
  sheet shows one note line saying so, and the engine's rows still load.
- **Voice mode.** The speaker button in a chat's toolbar turns it on (off by default, per chat). The first time, iOS
  asks for speech recognition and the microphone. Then a mic button sits beside Send: tap it and talk, tap again (or
  pause for two seconds) and what was heard, recognised on the phone by the Speech framework (on-device when the
  phone supports it), is sent as your message. Each reply is spoken by the system voice (`AVSpeechSynthesizer`) as
  it arrives, in order; the speaker-slash button stops it, and tapping the mic stops it too. What is spoken
  (`FirstMateKit`'s `SpeechText`): markdown marks are dropped, a ticket link or `#872` is read as "ticket 872",
  another link as its text, a bare URL as "link", a code block as "code omitted". Turning voice off stops listening
  and speech and releases the audio session: the chat is then text only. A denied permission or an audio failure
  shows in the chat's red error line; typing still works.

- **Push.** After sign-in the app asks to send notifications; when allowed it registers with APNs and posts the device
  token to `PUT /first-mate/push/device {deviceToken, platform: "ios", environment}` (`sandbox` from a Debug build,
  `production` from TestFlight and the App Store), on every launch and whenever APNs hands it a new one (the old one is
  unregistered). Sign out sends `DELETE /first-mate/push/device`. The engine (engine/README.md, "Push notifications")
  pushes when a row of its Needs list appears for you (a question, an approval, an acceptance, a decision)
  and when one of your chats has answered (the reply's stream-json `result` line). Tapping a chat notification opens
  that chat; a Needs one on a ticket opens the ticket's read-only sheet; one with no ticket opens the Needs sheet on that
  row. A push for a HITLP task waits on the HITLP server sending one (the codefrak-oss/hitlp ask): the engine no
  longer relays them. `FirstMate.entitlements` carries `aps-environment` and `Info.plist` the
  `remote-notification` background mode.

  Human steps, once: in the Apple Developer portal enable Push Notifications on the app id `com.codefrak.firstmate`
  (then refresh the profiles: `fastlane match appstore --force`), and create a key under Keys with Apple Push
  Notifications service; store the `.p8`, its Key ID and the Team ID in the 1Password item "frakworks iOS signing"; set
  them on the engine with `PUT /push/apns {"p8": "<the .p8 text>", "keyId", "teamId", "bundleId":
  "com.codefrak.firstmate", "environment": "production"}` (a first-mate token is not needed; `GET /push/apns` then says
  `set: true`); rebuild with `fastlane beta`. The lane does not read the key, so `fastlane/.env.example` is unchanged.

The phone reaches the engine at the pairing's URL: on the tailnet, or wherever the engine's public URL is.

## Tests

On a Mac (the Smith sandbox has no Swift toolchain):

```
cd ios/FirstMateKit && swift test        # SSEParserTests, TicketLinkTests, PairingTests, EngineClientRetryTests, SpeechTextTests, PushPayloadTests
```

or in Xcode, open `FirstMateKit/Package.swift` and press ⌘U. The app itself: open `FirstMate/FirstMate.xcodeproj`, pick
an iOS 17 simulator or your iPhone, Run. Voice mode needs the iPhone: the simulator has no microphone input.

## TestFlight

`fastlane beta` runs **on the user's Mac**, which has Xcode and the Apple Developer account; it is not run by the
engine or a Smith. The signing secrets come from 1Password: copy `fastlane/.env.example` to `fastlane/.env`
(gitignored) and fill it from the 1Password item (team id, App Store Connect API key id, issuer and `.p8` as base64,
the match repo URL and password), then:

```
cd ios && bundle exec fastlane beta      # or: fastlane beta
```

It fetches the App Store certificate and profile with match (read-only), bumps the build number past TestFlight's
latest, builds and uploads.
