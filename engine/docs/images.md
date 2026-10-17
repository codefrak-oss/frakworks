# Images

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

Every push to `main` that touches `engine/**` (or, for the Smith image,
`bots/smith.md`, `bots/model-choice.md`, or the desktop's `package.json` and
`package-lock.json`, whose `node_modules` the Smith image carries) builds both images, for
amd64 only (the engine and its Smiths run on the cluster's amd64 nodes), on
the cluster's `frakworks-images` runner scale set and publishes them to
the LAN registry (`.github/workflows/images.yml`; run it by hand with
`workflow_dispatch`). The runner pods have no Docker daemon: every CI build
runs on one shared buildkitd in the cluster, whose persistent state keeps
the Gradle cache mount between builds (the runners and buildkitd are
declared in the site's GitOps repo (`platform/frakworks-ci/` and
`apps/frakworks-ci-*.yaml`)).
After the Smith image is pushed, the job runs `cd observability/desktop && npm test`
in it with the network off, against the workflow's checkout, and fails unless it
passes on the baked `node_modules` alone.
The registry is plain HTTP with no auth and reachable on the LAN only; a
client's Docker must list it under `insecure-registries`. Its name is a
deployment value: the Actions secret
`REGISTRY` (masked in the logs) for the workflow, `FRAKWORKS_REGISTRY` in `.env` for the local
stack, and `registry` in the desktop's `agent-army.config.json`; this doc
writes it as `registry.example:5000`. Not GHCR: the repo is private and the
images are large, so they would burn the org's package quota.

Two tags per image, `:main` (the latest build of `main`) and
`:sha-<first 7 of the commit>`:

```
docker pull registry.example:5000/frakworks-engine:main
docker pull registry.example:5000/frakworks-engine:sha-1234abc
docker pull registry.example:5000/frakworks-smith:main
docker pull registry.example:5000/frakworks-smith:sha-1234abc
```

The local stack builds both images by default and uses a registry only when
`FRAKWORKS_REGISTRY` names one (the seam for a public registry later):
`engine/compose.local.yaml`'s engine service is
`image: ${FRAKWORKS_REGISTRY:+$FRAKWORKS_REGISTRY/}frakworks-engine:main`, and
`ENGINE_SMITH_IMAGE` defaults to the same form of `frakworks-smith:main`.
Unset, both are the registry-less tags `scripts/local-up.sh` builds; set, the
registry's `:main`.
**Deploying on Kubernetes** is by hand, not continuous (the user, 2026-10-11:
the user deploys when a ~45 s engine gap won't interrupt them): images.yml
only builds and pushes the images. A deploy is a call to the engine's
`POST /engine/restart` (with a trusted login's token), which patches the engine's
own Deployment as `kubectl rollout restart` does (the base's Role
`engine-self-restart` lets its ServiceAccount get and patch that one
Deployment), so the new pod pulls `:main`; `GET /version` then reports the new
commit. The endpoint answers 401 without a token, 409 outside a cluster and 403
to a Smith sandbox.
With `?argoApp=<namespace>/<name>`, the
engine first waits for that Argo CD Application to be synced: it asks Argo for a
hard refresh, then waits up to 5 minutes for a reconcile since the ask that ends
Synced with no sync operation running, and answers 504 without restarting when
none comes. Argo syncs `main`'s head, which contains the commit being deployed,
so a commit that changes the engine and `deploy/k8s/base` together (a
PriorityClass the new engine names, on 2026-10-06) never runs the new engine
against the old manifests. The get and patch on that one Application are the
site's to grant (the overlay), since the base does not assume Argo.

**The desktop's Deploy button** (#2416) is how the user times a deploy: the
engine owns how, the app owns when. `GET /engine/update` is the status:
`mode` (`kubernetes`, or `host` outside a cluster), `selfUpdate`, `running`
(`/version`'s commit), `image` (read off the engine's own Deployment with the
Role's get), `latest` (the registry's newest `:main`: its digest and the
commit and source of its `org.opencontainers.image.revision` and `.source`
labels, read anonymously, a public registry such as GHCR handing out a pull
token, so no secret; read over https, and over plain http only when the
registry answers https with a TLS error (an `SSLException`, as the plain-HTTP
LAN registry does: Docker's `insecure-registries` rule, with no setting); a
refused connection, a timeout or a 4xx/5xx over https is the error as it is,
and a lookup that fails over both carries `registry: https: <error>; http:
<error>` in `error`), `behind` (commits from `running` to `latest`, GitHub's
public compare on the source repo; null when unknown), `updateAvailable`,
`openSessions` (first-mate sessions open or frozen) and `error`. It is kept a
minute (`?fresh=true` asks again). `POST /engine/update` is the action: on
Kubernetes exactly `POST /engine/restart` (with its `argoApp`); outside a
cluster a 409 saying the host updates it, which for a Compose engine is the
desktop's pull and recreate (`observability/desktop/README.md`, "Update
engine"). The button confirms naming the live Smith runs and the open
first-mate sessions, shows "deploying…" through the gap, and checks
`GET /version` for the new commit.

The engine runs `:main` with `imagePullPolicy: Always`, so **any** pod restart
(a crash, a node drain) also picks up the newest image, not only a deploy from
the button.

**Deploying the engine** with Compose and a registry is pull and recreate, no build:

```
docker compose pull engine && docker compose up -d engine
```

The desktop app's **Update engine** button does this for you (since #469,
`observability/desktop/README.md`, "Update engine"): it lights when the
registry's `:main` digest differs from the running engine container's, and
on a click, after a confirm naming the live Smith runs (which survive the
restart, `SmithTelemetryResume`), runs `docker compose pull engine` and
then `up -d --no-deps engine` in the container's own compose project. It
never acts on its own. The command above stays the fallback.

**A recreate with no network** (#1222). Three times on 2026-10-06 (03:46Z,
~04:20Z, ~08:10Z), on the host's Docker Desktop, the first `up -d
--no-deps engine` (or `--force-recreate engine`) after the engine image
changed (a local build tagged `registry.example.com:5000/frakworks-engine:main`, or
`docker compose pull engine`) recreated `frakworks-engine-1` with
`.NetworkSettings.Networks` `{}`. The JVM exits with
`java.net.UnknownHostException: engine-db`, and since the engine has no
`restart:` policy it stays exited. The same command run a second time
attaches `frakworks_default` and the engine starts.

The cause was not found. Ruled out in the repo: the compose files ask for
nothing unusual (the engine joins the implicit `default` network through
`include:` in `compose.yaml`, as `engine-db` does, and that network is the
one recreate attaches the second time); `--no-deps` is not it, since
`--force-recreate` without it showed the same; the project labels on the
recreated container are the compose project's (the desktop runner finds it
by them). That leaves a race in Docker Desktop or compose on the host
during recreate, which the repo cannot fix and the sandbox that
investigated it could not reproduce (no Docker there); the compose and
Docker Desktop versions of the sightings were not recorded. No compose file
was changed: a `restart:` policy would retry a JVM that can never resolve
`engine-db` in a container with no network, and an explicit `networks:`
entry names the same network the implicit one does.

What changed is the backstop: the desktop app's update runner checks the
recreated container's networks after `up` and recreates it once more when
the set is empty, failing the update if it is empty again
(`observability/desktop/README.md`, "The network check after `up`").
Deploying by hand, check after `up`:

```
docker inspect --format '{{json .NetworkSettings.Networks}}' frakworks-engine-1
```

`{}` means run `docker compose up -d engine` again (add `--force-recreate`
if compose reports the container up to date) and check once more.

**Which commit an image is.** `images.yml` passes `GIT_SHA=${{ github.sha }}`
to the engine build; the Dockerfile's runtime stage (last, so the layers
above stay cached) sets it as the `org.opencontainers.image.revision` label
and as `ENGINE_BUILD_COMMIT`, which `GET /version` reports as `commit`. A
local build has neither, and `commit` is null. The commit is for reading;
the exact running-versus-available comparison is by digest, which an image
cannot carry about itself: `docker image inspect --format '{{json
.RepoDigests}}'` on the running container's image against the registry's
`Docker-Content-Digest`.

The Smith image needs no step at all: the engine pulls it before a run and
refreshes `:main` at most once per `engine.smith.image-refresh-interval`
("Smith runs"). To pin a build, set `ENGINE_SMITH_IMAGE` to its `:sha-<7>`
tag in `.env`.

**Building locally** is the default, `engine/compose.build.yaml`, which
`scripts/local-up.sh` uses when `FRAKWORKS_REGISTRY` is unset: it gives the
engine service `build:` and `frakworks-engine:main` and builds the `smith`
service as `frakworks-smith:main`, the image Smith runs use:

```
docker compose -f compose.yaml -f engine/compose.build.yaml --profile tools build engine smith
docker compose -f compose.yaml -f engine/compose.build.yaml up -d engine
```

(or `COMPOSE_FILE=compose.yaml:engine/compose.build.yaml` in `.env`). Without
it, `docker compose build engine` builds nothing: the engine's build and tests
alone are `docker build --build-context root=. --target build engine/` from the
repo root, what the engine check runs ("Configuration": the `root` context).
The `smith` service (profile `tools`) still builds `frakworks-smith:local`
with or without the override; point `ENGINE_SMITH_IMAGE` at it to use it.
