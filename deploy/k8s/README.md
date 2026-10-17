# deploy/k8s: the engine on Kubernetes

`base/` is a generic Kustomize base for running the engine, its Postgres and
its Smith Jobs on a Kubernetes cluster (k3s here). This is how the fleet's
engine runs; the desktop app and interactive sessions stay local. Docker
Compose stays the system-in-a-box model for local work (`engine/README.md`, "Hosting models"). It holds no site value: no hostname, IP
address, registry, storage class or certificate issuer. Those are an overlay's.

    kubectl kustomize deploy/k8s/base    # render it

## What the base declares

**`frakworks`**, the engine's namespace:

- Deployment `engine`: one replica (`RollingUpdate`, maxSurge 1,
  maxUnavailable 1), which holds the leader lease (a row in the engine's
  Postgres) and runs the schedulers, the job executor and the Smith log
  following. During a rollout the surge pod is the standby; only the lease
  holder is ready (`GET /ready`), so the Service sends traffic to it alone. The
  old pod's preStop (`GET /lease/await-standby` over loopback, at most 60 s)
  waits for a warm standby, then Spring's graceful shutdown releases the lease
  and the standby takes it within 1 s (engine/README.md, "Replicas and the
  lease"). Required pod affinity keeps the surge pod on the same node, since the plugin jar
  PVC is ReadWriteOnce. Image `frakworks-engine:main` pulled
  `Always`, 2 GiB memory limit, running as ServiceAccount `frakworks-engine`
  (the Kubernetes client's in-cluster credential). Settings from ConfigMap
  `engine` and Secret `engine`; the plugin jars on PVC `engine-jobs` at
  `/var/lib/frakworks/jobs`. No Docker socket: `smith.hosting` is
  `kubernetes`, and `engineUrl` is `http://engine.frakworks.svc:8080`.
- Service `engine` on port 8080.
- Deployment and Service `web` (port 8080): the web version of the desktop
  app, image `frakworks-web` (nginx serving `dist-web/`;
  `observability/desktop/README.md`, "Web version"), and Ingress `web`
  sending `/web` to it, with no host (the overlay sets it).
- StatefulSet `engine-db` (Postgres 16, 1 GiB limit) with a 10 Gi
  `volumeClaimTemplate` and no `storageClassName` (the cluster's default
  unless the overlay names one), behind headless Service `engine-db`.
- NetworkPolicy `engine-ingress`: ingress from within `frakworks`, and from
  `frakworks-smith` and `frakworks-firstmate` on the engine's port only, so
  Postgres is unreachable from a Smith or a first mate.

**`frakworks-smith`**, Smith Jobs only:

- Pod Security `restricted`: the cluster refuses a Smith pod that is not
  locked down.
- Role `frakworks-engine` (Jobs; pods, `pods/log`, `pods/exec`; Secrets for
  each run's env) and a RoleBinding to `frakworks:frakworks-engine`. No
  ServiceAccount of its own: a Smith pod mounts no token.
- ResourceQuota `smith-memory`: requests 14 Gi memory and 7 CPU, limits
  48 Gi and 32 CPU: at least 12 runs at once at the default pod requests
  (1 GiB, 0.5 CPU: `PUT /settings/smith-hosting`) and limits
  (`engine.smith.memory-bytes` 3 GiB, `engine.smith.cpus` 2). Smith pods are
  Burstable, so the limits overcommit the nodes on purpose.
- PriorityClasses `frakworks-smith` (value 0, never preempts; every Smith pod)
  and `frakworks-core` (the engine and Postgres, whose memory requests equal
  their limits): under node pressure the kubelet evicts Smiths first, and the
  engine reruns an evicted run, up to three times, then parks it on an
  incident naming eviction (`engine/README.md`, "Incidents").
- NetworkPolicy `smith-sandbox`: no ingress; egress to the cluster DNS, the
  engine Service's port in `frakworks` (selected by namespace), and the
  internet, not the private ranges (the LAN, the rest of the cluster).

**`frakworks-firstmate`**, the iPhone first mate's pods
(`ios/README.md`), and the desktop's First mate's when
`firstmate.hosting` is `kubernetes`:

- Pod Security `restricted`.
- ServiceAccount `first-mate` with no token mounted and no binding: it cannot
  create Jobs or read a Smith's Secrets.
- Role `frakworks-engine` (pods get/list/watch/create/delete, `pods/attach`,
  `pods/log`; Secrets create/delete) and a RoleBinding to
  `frakworks:frakworks-engine`.
- NetworkPolicy `firstmate-sandbox`: no ingress; egress to the cluster DNS,
  the engine's port and the internet outside the private ranges. With
  `smith-sandbox`, Smith and first mate pods cannot reach each other.

Telemetry is the overlay's: the base runs no VictoriaLogs and no scrape, and
ConfigMap `engine` leaves `ENGINE_TELEMETRY_LOGS_URL` and
`ENGINE_TELEMETRY_METRICS_URL` blank (off) for the overlay to point at the
site's.

## The overlay

**Images.** The base names `frakworks-engine:main` and `frakworks-web:main`
with no registry, and stays registry-free: on its own it runs only where the
nodes already have those images. Give it one of:

- an overlay `images:` entry per image naming your registry:

  ```yaml
  resources: [../base]   # or a remote ref to deploy/k8s/base
  images:
    - name: frakworks-engine
      newName: registry.example:5000/frakworks-engine
    - name: frakworks-web
      newName: registry.example:5000/frakworks-web
  ```

  (the Smith image is a setting, `PUT /settings/smith-hosting`'s `image`,
  not a manifest);
- or a local build loaded into the cluster, e.g. for kind
  `docker build --build-context root=. -t frakworks-engine:main engine/` then
  `kind load docker-image frakworks-engine:main` (minikube: `minikube image
  load`), with the Deployments' `imagePullPolicy: Always` patched to
  `IfNotPresent` in the overlay so the node does not try to pull it.

An overlay (for this lab, `platform/frakworks/` in the site's GitOps repo,
synced by Argo CD) references the base and supplies what only the site knows:

- `images:` the registry the nodes pull `frakworks-engine` from;
- a patch on `engine-db`'s `volumeClaimTemplates` and `engine-jobs` for the
  storage class and sizes; the quota and limits if the site's differ;
- a ConfigMap `engine` patch: the `ENGINE_TELEMETRY_*` URLs;
- an Ingress (hostname, TLS issuer) for the desktop app, and a rule in
  `engine-ingress` for the ingress controller's namespace. It carries
  authenticated traffic only: the engine refuses every request but
  `/health`, `/ready`, `/hooks/github` and the frakpanel tile reads without
  a bearer token (`engine/README.md`, "Tokens").
- `images:` the registry for `frakworks-web` too, and a patch on Ingress
  `web` giving it the engine Ingress's host, TLS and ingress class, so
  `<host>/web/` reaches the web version (`observability/desktop/README.md`,
  "Web version") on the engine's origin. Its pod listens on 8080 like the
  engine, so the ingress controller's rule in `engine-ingress` covers it.
  After the first deploy behind the Ingress, tell the engine its address:
  `PUT $ENGINE_URL/settings/public-url` with `{"publicUrl": "https://<host>"}`
  and a bearer token (`engine/README.md`, "The public URL"). The engine cannot
  read the overlay's hostname, and First mate's `read_public_url` answers
  nothing until it is set.

## Each target repo's two tokens

A target repo needs two GitHub tokens set through the engine's API, never a
Secret: the repo token (`PUT $ENGINE_URL/repos/<o>/<n>/token`), which the
engine uses for PRs, merges and publishing, and the Smith token (`PUT
$ENGINE_URL/repos/<o>/<n>/smith-token`), a fine-grained PAT with Contents and
Pull requests read/write and no Issues permission, which each Smith Job gets as
`GH_TOKEN` in its run's Secret. Without a Smith token, or with one that can
reach the repo's issues, no Smith Job starts (`engine/README.md`, "Each repo's
token").

## Secrets

Created with `kubectl`, from the password manager, never in git:

    kubectl -n frakworks create secret generic engine \
      --from-literal=db-password=... --from-literal=secrets-key=...

`db-password` is Postgres's and the engine's datasource password;
`secrets-key` is `ENGINE_SECRETS_KEY`, the engine's sealed store.

The HITLP server the engine files its flow waits on as a requester
(codefrak-oss/hitlp `spec/hitlp.md` §7; `engine/README.md`, "The HITLP
requester") is not in the Secret or the ConfigMap (#2500): its MCP endpoint,
bearer token are settings in the engine's database, sealed
where secret. After the first deploy, set them once with a bearer token:

    curl -X PUT $ENGINE_URL/settings/hitlp -H "Authorization: Bearer frk_..." \
      -H "Content-Type: application/json" \
      -d '{"mcpUrl": "https://<hitlp host>/mcp", "requesterToken": "<token>"}'

The engine is a requester only: the desktop and the phone reach the HITLP
server's surface API directly with each person's own credential, never through
the engine. A deployment from before #2500 moves its values over the
same way, then drops `hitlp-token` from the `engine` Secret and
`ENGINE_HITLP_URL` from its overlay's ConfigMap: the engine reads neither.

## Telling the engine of a deploy (Argo CD)

A ticket whose PR has a post-deployment check waits for its merge to reach
production (`engine/docs/post-deploy-check.md`). The engine learns of a deploy
from `POST /repos/{owner}/{name}/deploys {sha, source}`
(`engine/README.md`, "Deploys"); for its own repo it posts one itself at start.
For any other repo Argo CD can post it on each successful sync, through
argocd-notifications. In `argocd-notifications-secret`, `engine-token` is a
login's bearer token (`frk_...`); in `argocd-notifications-cm`:

```yaml
data:
  service.webhook.frakworks: |
    url: https://<engine host>
    headers:
      - name: Authorization
        value: Bearer $engine-token
      - name: Content-Type
        value: application/json
  template.frakworks-deployed: |
    webhook:
      frakworks:
        method: POST
        path: /repos/{{index .app.metadata.annotations "frakworks.codefrak.com/repo"}}/deploys
        body: |
          {"sha": "{{.app.status.sync.revision}}", "source": "argocd {{.app.metadata.name}}"}
  trigger.on-deployed-frakworks: |
    - when: app.status.operationState.phase in ['Succeeded'] and app.status.health.status == 'Healthy'
      oncePer: app.status.sync.revision
      send: [frakworks-deployed]
```

Then annotate each Application whose repo the engine works:

```yaml
metadata:
  annotations:
    frakworks.codefrak.com/repo: <owner>/<name>
    notifications.argoproj.io/subscribe.on-deployed-frakworks.frakworks: ""
```

`oncePer` posts each revision once; a repeat is harmless (the engine records
it and a flow already woken is not woken twice). The 12 h safety poll still
catches a deploy no notification reported.

## An engine outside the cluster

An engine still in Compose that starts Smith Jobs on the cluster reads a
kubeconfig instead (`engine/README.md`, "Hosting models", "Setting up k3s"
steps 3 and 4). Its credential is a token for the same ServiceAccount, created
with `kubectl`:

    kubectl -n frakworks apply -f - <<'EOF'
    apiVersion: v1
    kind: Secret
    metadata:
      name: frakworks-engine-token
      annotations: {kubernetes.io/service-account.name: frakworks-engine}
    type: kubernetes.io/service-account-token
    EOF

and the overlay adds the engine host's address to `smith-sandbox`'s egress.
