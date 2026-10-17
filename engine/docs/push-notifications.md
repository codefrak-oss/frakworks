# Push notifications

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

The iPhone first mate's APNs pushes (module `engine-firstmate`, package
`firstmate.push`). The
engine sends them; the phone registers its device token and opens the right place
when one is tapped (ios/README.md, "Push").

- **What is pushed, to whom.** A `needs` frame with state `added` (a row of `GET
  /needs` appeared: a question, an approval, an acceptance, a decision, a HITLP
  task) goes to every device of the login the row is `for`, and a row for
  everyone (`for` null) to every registered device: the first mate's logins are
  the user's own, so "everyone" is them. `removed` pushes nothing. The body names
  the kind and `<repo>#<n>`, or the HITLP task's action when it has no ticket
  ("A question waits for you: acme/app#12"); the payload's `fm` is `{"type":
  "needs", kind, repo, number, actionId}` and the app opens the ticket's sheet,
  or its Needs list for a task with no ticket. A first-mate session's outbox
  storing a stream-json `result` line (what ends one reply; an `assistant` line
  does not) pushes "First mate answered" to the session owner's devices alone,
  with `fm` `{"type": "chat", session, repo}`; the app opens that chat. Nothing
  else is pushed.
- **Off the request path.** `EngineEvents.onPublish` hands each frame published
  on this replica (a relayed one is not, so a push goes out once) to
  `PushNotifier`, and the outbox endpoint calls it per stored line; both only
  queue the push. One `apns-push` thread sends them from a queue of 500 (a full
  queue drops the push, at DEBUG). With no credentials set, or no device to send
  to, nothing is sent and the log says so at DEBUG only.
- **Answers.** 200 is sent. 410, or reason `Unregistered`, `BadDeviceToken`,
  `DeviceTokenNotForTopic` or `ExpiredToken`, deletes the device row (INFO). Any
  other answer, a refused connection or a timeout is logged once at WARN with
  the status and APNs's reason and is not retried: the next push tries again.
- **The client.** Hand-rolled, no dependency: `JdkApnsClient` posts `POST
  /3/device/<token>` over HTTP/2 with the JDK's `HttpClient` to
  `api.push.apple.com` (production) or `api.sandbox.push.apple.com` (sandbox),
  with headers `authorization: bearer <JWT>`, `apns-topic` (the bundle id),
  `apns-push-type: alert` and `apns-priority: 10`. `ApnsJwt` signs the provider
  token, ES256 (`SHA256withECDSAinP1363Format`) with the `.p8` key, header
  `{alg, kid}` and claims `{iss: team id, iat}`, and keeps it 50 minutes (Apple
  refuses one older than an hour and throttles renewing more than every 20).
  A library (Pushy, a JWT library) would add Netty or another HTTP stack for what
  the JDK does in two classes. The host is the device's `environment` (a
  Debug build registers `sandbox`, TestFlight and App Store builds
  `production`); one key works on both. The cluster's `frakworks` namespace has
  no egress policy, so the engine reaches port 443 as it does GitHub.
- **Devices.** `PUT /first-mate/push/device {deviceToken, platform?: "ios",
  environment?: "sandbox"|"production"}` (default `production`) stores the
  phone's APNs device token (hex) for the caller's login and token: 201 new, 200
  when the same device token was registered (the row is updated, never
  doubled, and moves to the new login and token). `DELETE /first-mate/push/device
  {deviceToken}` removes the caller's row (204; 200 when it had none). Only a
  `first-mate` token is accepted, as on `/first-mate/sessions` (401 without one,
  403 with another), and a Smith sandbox is 403. Revoking the token (`DELETE
  /tokens/{id}`) deletes the devices it registered.
- **Credentials.** `PUT /push/apns {p8, keyId, teamId, bundleId, environment}`
  (the `.p8` file's PEM text, the 10-character Key ID and Team ID, the app's bundle
  id, which is the APNs topic, and the default host for a device that names none)
  stores them as one row of `engine.engine_setting` named `push.apns`, sealed
  under `ENGINE_SECRETS_KEY` (the name is the associated data): 204, 503 while the
  key is not set (as `PUT /repos/{owner}/{name}/token`), 400 for a `p8` that is
  no PKCS#8 EC key. `GET /push/apns` answers `{set, setAt}` and never the key;
  `DELETE /push/apns` clears them (204; 200 when none were set). A Smith sandbox
  is 403 on all three. No property, environment variable or `.env` line carries
  them.
- **Table.** `engine.push_device` (V79): `device_token` (primary key), `login`,
  `token_id` (the `engine.engine_token` that registered it, on delete cascade),
  `platform`, `environment`, `created_at`, `updated_at`.
