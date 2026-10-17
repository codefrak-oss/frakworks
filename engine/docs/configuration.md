# Configuration

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

A setting has one home, and a setting that moves or goes leaves nothing behind
(`CLAUDE.md`, "A setting that moves leaves nothing behind"). Two things enforce
it.

**The build's tests** (`ConfigConsistencyTest`, plain JUnit) fail the engine
check, naming every offending variable or key, on any of three rules:

1. **Compose against `.env.example`.** The `${VAR}` names `compose.yaml`,
   `engine/compose.local.yaml` and `observability/compose.local.yaml` read, plus
   what `scripts/local-up.sh` writes into `.env`, are exactly the keys
   `.env.example` declares (an uncommented `KEY=` line). `.env.example` is the
   declared list of what a deployment sets: every variable compose reads,
   optional overrides included, each empty with its default in a comment (or
   set to the default, where compose reads an empty value as off). Compose's own variables (`COMPOSE_PROJECT_NAME`) are a short list
   in the test, each with its reason.
2. **The engine container against `application.properties`.** Every `ENGINE_*`
   variable the `engine` service in `engine/compose.local.yaml` sets binds, by
   Spring's relaxed binding, to a key of `application.properties`, and every
   `SPRING_*` one is under an allowed Spring prefix (`spring.datasource.`).
3. **`application.properties` against the code.** Every `engine.*` key in it is
   read by main code (a `${engine.…}` placeholder in `@Value` or `@Scheduled`,
   an `@ConditionalOnProperty`, or a `@ConfigurationProperties` prefix), every
   other key is under a listed framework prefix (`spring.`, `server.`,
   `management.`, `logging.`, `operaton.`), and every `engine.*` key the code
   reads is declared there, with or without a value.

The root files sit outside `engine/`, the Docker build's context, so every build
of `engine/Dockerfile` passes the repo root as a second, named context, `root`:
`--build-context root=.` in the engine check (`.github/workflows/engine.yml`),
in `images.yml` and in this README's commands, `additional_contexts: root: .` in
`engine/compose.build.yaml`. The build stage copies the files to `/repo` and sets
`FRAKWORKS_REPO_ROOT=/repo`; in a checkout (`cd engine && gradle test`) the test
reads them from `..`. A build without the context fails at the `COPY --from=root`;
a missing file fails the test, naming its path.

**The engine refuses unknown `engine.*` properties at startup**
(`UnknownEngineProperties`, an `EnvironmentPostProcessor` registered in
`META-INF/spring.factories`). From every property source Spring has loaded (an
`ENGINE_*` environment variable, a system property, a command-line argument, a
config file), an `engine.*` name that is not a key of the classpath
`application.properties`, compared in relaxed form (case and `.`, `_`, `-`
ignored), stops the start before any bean exists, so no flow or scheduler runs,
and the process exits non-zero:

```
java.lang.IllegalStateException: Unrecognised engine.* properties, not keys of application.properties (engine/README.md, "Configuration"); delete them or declare them:
  ENGINE_CI_CHECK_REPOS (from systemEnvironment)
  engine.nope (from commandLineArgs)
```

Anything not under `engine.` (`SPRING_*`, `PATH`, `JAVA_TOOL_OPTIONS`) is left
alone. The test JVM gets no `ENGINE_*` variable from the build's environment
(`build.gradle.kts`): a Smith sandbox's `ENGINE_URL` is the sandbox's, not the
engine's. A new setting is a key in `application.properties` first; a removed
one goes from there, its reads, compose, `.env.example` and this README in the
same change.
