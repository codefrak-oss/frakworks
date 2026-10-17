# syntax=docker/dockerfile:1
# Build stage: Gradle + JDK in the image, so no local toolchain is needed.
# --platform=$BUILDPLATFORM: the JAR is the same bytes on every architecture,
# so a multi-arch build (.github/workflows/images.yml, amd64 + arm64) runs
# Gradle once, natively, and only the runtime stage below is built per arch.
FROM --platform=$BUILDPLATFORM gradle:8.14-jdk21-alpine AS build
WORKDIR /src
# The image declares its default Gradle home a VOLUME, and Docker discards
# what build steps write to a volume; keep the cache in a normal path.
ENV GRADLE_USER_HOME=/gradle-cache
# The Gradle RUNs mount /gradle-cache as a BuildKit cache: on the persistent
# build runners (engine/ci/engine.yml) the downloads and build cache outlive
# each job's layer cache. The id is shared by every build of this Dockerfile
# on one BuildKit, and nothing from the mount lands in the image.
# Build files first: this layer (plugins + every dependency) is cached until
# they change, so a code-only change skips the downloads.
# gradle.properties too: it turns on the build cache (kept in the /gradle-cache mount), so a
# compile whose inputs an earlier build already compiled is restored, not run.
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
# The sdk module (settings.gradle.kts includes it as ../sdk) from the root context: the
# same `root` every build already passes for the files ConfigConsistencyTest reads below.
COPY --from=root sdk/ /sdk/
# The engine modules' build files (settings.gradle.kts includes modules/*), so the dependency layer resolves them too.
COPY modules/common/build.gradle.kts ./modules/common/
COPY modules/github-api/build.gradle.kts ./modules/github-api/
COPY modules/github/build.gradle.kts ./modules/github/
COPY modules/events/build.gradle.kts ./modules/events/
COPY modules/accounts/build.gradle.kts ./modules/accounts/
COPY modules/jev/build.gradle.kts ./modules/jev/
COPY modules/smith-api/build.gradle.kts ./modules/smith-api/
COPY modules/smith/build.gradle.kts ./modules/smith/
COPY modules/incidents/build.gradle.kts ./modules/incidents/
COPY modules/hitlp/build.gradle.kts ./modules/hitlp/
COPY modules/firstmate/build.gradle.kts ./modules/firstmate/
COPY modules/flow-intake/build.gradle.kts ./modules/flow-intake/
COPY modules/flow-feature/build.gradle.kts ./modules/flow-feature/
COPY modules/flow-delivery/build.gradle.kts ./modules/flow-delivery/
COPY modules/flow-doctor/build.gradle.kts ./modules/flow-doctor/
COPY modules/flow-friction/build.gradle.kts ./modules/flow-friction/
COPY modules/flow-proposal/build.gradle.kts ./modules/flow-proposal/
COPY modules/plugins/build.gradle.kts ./modules/plugins/
COPY modules/api/build.gradle.kts ./modules/api/
COPY modules/app/build.gradle.kts ./modules/app/
RUN --mount=type=cache,target=/gradle-cache,id=frakworks-engine-gradle gradle --no-daemon resolveDependencies
COPY modules ./modules
# First mate's prompt and allow/deny lists, shared with the desktop, on the classpath.
COPY --from=root shared/first-mate-prompt.json ./modules/app/src/main/resources/first-mate-prompt.json
COPY bpmn ./bpmn
COPY smith/schemas ./smith/schemas
# bots/refactor-values.md, which processResources copies from ../bots (the shipped refactoring values).
COPY --from=root bots/refactor-values.md /bots/refactor-values.md
# The repo-root files ConfigConsistencyTest cross-checks (engine/README.md,
# "Configuration"). They sit outside this context, so every build passes the
# repo root as a second, named one: `--build-context root=.` (the engine check,
# images.yml, the README's commands) or compose.build.yaml's additional_contexts.
COPY --from=root .env.example compose.yaml /repo/
COPY --from=root engine/compose.local.yaml /repo/engine/
COPY --from=root observability/compose.local.yaml /repo/observability/
COPY --from=root scripts/local-up.sh /repo/scripts/
ENV FRAKWORKS_REPO_ROOT=/repo
# The shared remote Gradle build cache (settings.gradle.kts, buildCache): CI passes the node's URL and whether
# to push; with no build args the build uses the local cache only.
ARG GRADLE_REMOTE_CACHE_URL
ARG GRADLE_REMOTE_CACHE_PUSH
ENV GRADLE_REMOTE_CACHE_URL=$GRADLE_REMOTE_CACHE_URL GRADLE_REMOTE_CACHE_PUSH=$GRADLE_REMOTE_CACHE_PUSH
# --console=plain prints one line per task with its outcome (> Task :smith:test FROM-CACHE,
# EXECUTED, UP-TO-DATE), so every engine check log records what the build cache restored.
RUN --mount=type=cache,target=/gradle-cache,id=frakworks-engine-gradle gradle --no-daemon --console=plain build

FROM eclipse-temurin:21-jre
# git: the engine pushes a work Smith's commits itself (github/GitPush.kt).
# curl: the preStop hook calls GET /lease/await-standby on loopback (deploy/k8s/base/engine.yaml).
RUN apt-get update && apt-get install -y --no-install-recommends git curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
# engine-app (modules/app) is the one application; its boot jar is the image.
COPY --from=build /src/modules/app/build/libs/engine-app-0.0.1.jar app.jar
# The commit this image was built from (images.yml passes GIT_SHA; a local
# build leaves it empty). Declared last so every layer above stays cached:
# the OCI revision label is what the desktop's Update engine button reads
# from the registry, ENGINE_BUILD_COMMIT is `commit` on GET /version.
ARG GIT_SHA=
LABEL org.opencontainers.image.revision=$GIT_SHA
ENV ENGINE_BUILD_COMMIT=$GIT_SHA
# The repo the image is built from, as ENGINE_BUILD_REPO (engine.build.repo):
# the one repo whose production this engine is, for DeployedChecks and the
# deploy event the engine posts for itself at start (#100008).
ARG GIT_REPO=codefrak-oss/frakworks
ENV ENGINE_BUILD_REPO=$GIT_REPO
# The repo the image is built from: GET /engine/update counts commits behind
# on it (GitHub's compare).
LABEL org.opencontainers.image.source=https://github.com/codefrak-oss/frakworks
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
