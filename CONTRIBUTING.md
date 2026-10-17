# Contributing

## Build and test

- **Engine** (`engine/`, Kotlin, Spring Boot, Operaton): `docker compose build engine`
  builds it and runs its tests. Deploying: `deploy/k8s/README.md`; a local
  stack: `bash scripts/local-up.sh` (`docs/local-stack.md`).
  More in `engine/README.md`.
- **Desktop app** (`observability/desktop/`, Electron): `npm ci && npm test`
  in that folder. More in `observability/desktop/README.md`.
- **iOS app** (`ios/`): `ios/README.md`.

## How work flows

Work is filed as GitHub issues, and the engine works them as tickets: an ask is
refined into a ticket with acceptance criteria, an Agent Smith run implements
it on a branch, the engine opens the pull request and runs its checks, and a
review run checks it before merge. `engine/README.md` ("Smith runs", "Intake")
and `bots/smith.md` describe the process. Conventions for working in the code
are in `CLAUDE.md` and `AGENTS.md`.

Human contributions are welcome as pull requests too: keep a change to one
concern, include tests, and make sure the builds above pass. Report security
problems privately, never in an issue: see `SECURITY.md`. Everyone taking part
follows `CODE_OF_CONDUCT.md`.

## License

By contributing you agree that your contribution is licensed under the
repository's license, the MIT License (`LICENSE`).
