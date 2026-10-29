# The local stack: the whole army on one laptop

The system-in-a-box model: every component comes up from a clean clone with one
command, on a laptop, with only Docker installed.

```sh
bash scripts/local-up.sh
```

The script writes `.env` from `.env.example` with every secret minted (never
replacing one that is there), builds the engine and Smith images locally with
`engine/compose.build.yaml`, runs `docker compose up -d --build`, and prints
what is listening. Every step is safe to re-run. With `FRAKWORKS_REGISTRY` set
(environment or `.env`) it pulls the engine image from the registry instead and
the engine pulls the Smith image itself (`engine/README.md`, "Images").

## What runs

The root `compose.yaml` only `include:`s each component's own compose file, so a
component stays runnable from its own directory:

| Component | File | Host port (127.0.0.1 only) |
|---|---|---|
| engine | `engine/compose.local.yaml` | 8090 |
| observability: VictoriaMetrics, VictoriaLogs, Grafana | `observability/compose.local.yaml` | 8428, 9428, 3000 |

Services reach each other over the compose network by service name. The
desktop app is not a container: run it from `observability/desktop/` and enter
`http://localhost:8090` under Config > frakworks > Engine URL.

## Conventions

- **Secrets** come from the gitignored `.env` (template: `.env.example`) or files
  under the gitignored `secrets/`, never from an image or a compose file. Each
  target repo's GitHub token is given to the running engine
  (`PUT /repos/{owner}/{name}/token`, or Config > Repos in the desktop).
- **Ports** publish on `127.0.0.1` only.
- **Data** lives in named volumes: `docker compose down` keeps it, `down -v`
  wipes it.
