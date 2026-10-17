# Replicas and the lease

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

The engine runs as one replica (`deploy/k8s/base/engine.yaml`: `replicas: 1`,
`RollingUpdate` with maxSurge 1 and maxUnavailable 1). During a rollout the old
pod and the surge pod run side by side on one Postgres; the one holding the
lease runs the singleton work and is the only one ready, so traffic follows the
lease. A lone engine (the Compose stack, the desktop's system-in-a-box, an
engine run by hand) takes the lease at startup and behaves as one engine always
did; it needs no setting.

**The rollout handover.** The surge pod starts as the standby: it fails to take
the row, writes itself into `standby` and `standby_at` (V78) and tries again
every 1 s. The old pod's preStop runs `curl http://127.0.0.1:8080/lease/await-standby`
(`AwaitStandbyController`: loopback only, no token; an `exec` hook, as an
`httpGet` hook would come from the kubelet to the pod IP). From its start the old
pod's `/ready` answers 503; it blocks until a standby other than itself wrote
within 3 s, or 60 s have passed (a warning in the log; the pod's
`terminationGracePeriodSeconds` is 90). Then SIGTERM stops the singleton work and
frees the row, and the standby takes it within 1 s and turns ready: the
handover gap is the readiness probe's period plus that second, which the
clients ride out (503 and refused connections are retried). A pod deleted with
no rollout (a node drain) has no standby and waits out the 60 s.

A kind/k3d rollout smoke test is not part of the engine check: its build runner
has no Docker daemon to run a cluster in, so the handover is covered by
`LeaseHandoverTest` (two engine contexts on one database, real heartbeat
threads) and checked by hand on a rollout with the desktop Deploy button.

**Instance id.** `InstanceId` is the hostname (`HOSTNAME`, else the machine's
name): the pod's name on Kubernetes, `engine` in the Compose stack
(`hostname: engine` in `compose.local.yaml`). It is the lease's holder, the
`host` label and field of the transcript rows and `smith_run_active`, and the
event relay's origin.

**The lease** (`EngineLease`, the one row of `engine.engine_lease`: holder,
expiry, heartbeat). Every 5 s the holder, and every 1 s a standby, runs one conditional `UPDATE` that
takes or renews it when it is free, expired or already its own, for 15 s on. The
first attempt runs as the application becomes ready, before the other startup
work, so a lone engine's startup is unchanged. A graceful stop (Spring's
`server.shutdown=graceful`; the lease stops first among the lifecycle beans)
stops the singleton work, then frees the row, and the peer takes it on its next
heartbeat; a killed holder's row expires within 15 s. A holder whose renewal
fails gives the lease up while its last expiry is still a heartbeat away, so two
never run the singleton work at once. Taking it publishes `LeaseAcquired`, and
losing or releasing it `LeaseLost`, to the beans below.

**Under the lease:**

- every `@Scheduled` tick returns at once on a follower: the scan clock
  (`ScanSchedule`, which also scans on taking the lease, for what happened while
  no leader scanned), the doctor's tick and bug sync, the friction fix, bug and
  grouper ticks, the job ticker (`JobTickClock`: a due job starts once), the
  Smith sweep and the exit backstop, and the hitlp sweep (`HitlpTick`, "The
  hitlp server": a task's default action at its deadline, and retention);
- the Operaton job executor (`JobExecutorLease`): started on `LeaseAcquired`,
  shut down on `LeaseLost` and on a follower at startup. Every async
  continuation, timer and plugin flow job therefore runs on the holder, which
  loaded the plugin jars; a follower that never loaded a jar never executes its
  flow's jobs. A follower's HTTP calls still start processes and complete tasks
  synchronously in Postgres, and the holder's executor picks up the jobs they
  leave;
- the Smith exit watch (`SmithExitNotifier`): subscribed, and the missed exits
  scanned, on `LeaseAcquired`; closed on `LeaseLost`;
- following live Smith runs for their transcripts, live tallies and
  `smith_run_active` (`SmithTelemetry`, `SmithSessions`): on `LeaseAcquired`
  every run waiting on its exit whose sandbox still runs is followed from now
  (`SmithTelemetryResume`, the same path as a restart); on `LeaseLost` each is
  let go without ending its session (`SmithTelemetry.handOff`), and the new
  holder's writer appends to the same session. A follower answers a run's live
  tally from the stored lines, read on each call.
- webhook wakes (`WebhookWake`, #1802): a delivery reaches whichever replica the
  Service picks, and a follower does not scan; it hands the wake to the holder
  through the one row of `engine.webhook_wake` (V64, `LeaseWakeLeader`), which
  the holder takes on its next second (`WebhookWake.relayed`) and wakes as if
  the delivery were its own. Until #1802 a follower's delivery ran a full scan
  there, alongside the holder's. `POST /scan` (`ScanController`) is the one scan
  a follower still runs, on an explicit request.

Per-instance caches stay per instance (the GitHub call meter, the webhook wake
throttle, scan costs, setup checks).

**The event relay** (`EventRelay`). `EngineEvents.publish` offers a frame to this
replica's subscribers and sends it to the others; `PostgresEventRelay` NOTIFYs
channel `engine_events` with `{"o": origin, "r": repo, "f": frame}`, from one
sender thread in publish order. A frame whose payload would pass Postgres' 8000
bytes goes into `engine.event_relay` and the notification carries its id; rows
older than a minute are deleted as new ones are added. Each replica LISTENs on a
connection of its own (outside the pool), skips what it sent itself and hands
the rest to its subscribers, so a client on any replica sees every event. A
dropped listener reconnects after 2 s; frames sent meanwhile are lost, as a
client's own reconnect loses them. `engine.events.relay` is `postgres`;
`noop` (`NoopEventRelay`) is the tests' setting only, as H2 has no NOTIFY.

**Readiness.** `GET /ready` (unauthenticated) answers 200 when the database
answers, the lease runs, the relay listens and this replica holds the lease;
503 otherwise, with each check, the instance, the holder and a `reason`
(`lease held by <holder>` on a standby), and 503 with `shutting down` from the
start of the preStop on. The Kubernetes readiness probe reads it; the liveness
probe is unchanged.
