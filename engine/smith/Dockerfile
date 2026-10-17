# The Smith sandbox — one container per run, started by the engine as a
# sibling container through the Docker socket (engine/README.md, "Smith runs").
# Everything a Smith needs and nothing the engine has: Claude Code, git, gh,
# python3 (with jsonschema, for check-result). No Docker socket, and no
# credential but the ticket's repo's token and the one Claude credential the
# engine hands it per run.
# JDK 21 + Gradle so a Smith can run the engine test class it changes in-sandbox:
# `cd engine && gradle :engine-<module>:test --tests <Class> --no-daemon --offline`
# (engine/README.md, "Smith runs"); never the full build. No apt source or signing key needed.
# - The JDK comes from eclipse-temurin:21-jdk: glibc, like this image, and the
#   same Temurin 21 the engine runs on (engine/Dockerfile's runtime stage).
# - Gradle comes from the exact image engine/Dockerfile builds with (keep the tag
#   in step with its build-stage FROM). Gradle is plain JVM code, so the Alpine
#   image's copy runs on glibc. Not the Debian gradle:8.14-jdk21: on 2026-09-25
#   the copy cached on the build host had a zero-byte /opt/gradle (every file
#   empty), and the image built from it had a `gradle` that did nothing and
#   exited 0.
# The desktop's node_modules (observability/desktop), so a Smith can run its
# `npm test` with the network off: `npm ci` from compose's `desktop` build
# context (package.json and package-lock.json) into /opt/desktop-deps, with
# ELECTRON_SKIP_BINARY_DOWNLOAD=1 (no Electron binary: the tests are esbuild
# plus node --test). run-smith copies it into the clone. Installed on this
# image's Node 22, which the lock file accepts (the engine check runs the
# tests on Node 24). --ignore-scripts because the desktop's own postinstall
# (name-dev-electron.js, a no-op off macOS) is not copied; esbuild's install
# script is run on its own. images.yml rebuilds the image when either file changes.
FROM eclipse-temurin:21-jdk AS jdk
FROM gradle:8.14-jdk21-alpine AS gradle
FROM node:22-bookworm-slim
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
COPY --from=gradle /opt/gradle /opt/gradle
ENV JAVA_HOME=/opt/java/openjdk \
    GRADLE_HOME=/opt/gradle \
    PATH=/opt/java/openjdk/bin:/opt/gradle/bin:$PATH
# Keeps the Gradle client from taking a quarter of the sandbox cap as its heap; the
# build JVM's heap, workers and test forks are smith/gradle.properties' (below).
ENV GRADLE_OPTS="-Xmx256m"
RUN apt-get update && apt-get install -y --no-install-recommends \
        git curl ca-certificates python3 python3-jsonschema jq \
    && curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg -o /usr/share/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" > /etc/apt/sources.list.d/github-cli.list \
    && apt-get update && apt-get install -y --no-install-recommends gh \
    && rm -rf /var/lib/apt/lists/*
RUN npm install -g @anthropic-ai/claude-code && npm cache clean --force
COPY --from=desktop package.json package-lock.json /opt/desktop-deps/
RUN cd /opt/desktop-deps && ELECTRON_SKIP_BINARY_DOWNLOAD=1 npm ci --no-audit --no-fund --ignore-scripts \
    && npm rebuild esbuild && npm cache clean --force
RUN useradd --uid 65533 --create-home --home-dir /home/smith smith \
    && mkdir -p /work && chown smith:smith /work
COPY run-smith.sh /usr/local/bin/run-smith
COPY git-askpass.sh /usr/local/bin/git-askpass
COPY meter-proxy.py /usr/local/bin/meter-proxy.py
COPY engine-proxy.py /usr/local/bin/engine-proxy.py
# A first-mate session pod's entry point (FirstMatePodSpec): claude's relay to the engine's /first-mate/pod/ endpoints.
COPY fm-relay.py /usr/local/bin/fm-relay
COPY settings.json /etc/smith/settings.json
# How a propose Smith writes its pitch page (engine/README.md, "Proposal"); the engine lints the page by its hard rules.
COPY pitch-guide.md /etc/smith/pitch-guide.md
# The framework docs every prompt reads, in any repo (engine/README.md, "Smith runs"):
# frakworks' own bots/ files, from compose's `framework` build context, so a
# Smith in another repo has them too and one in frakworks reads the same copy. The tier map
# is the engine's (a refine run's brief carries it), not a file here.
COPY --from=framework smith.md model-choice.md story-points.md /etc/smith/framework/
# The result contract: the engine checks result.json against these same files (engine/README.md, "Smith results").
COPY schemas/ /etc/smith/schemas/
COPY check-result.py /usr/local/bin/check-result
# The frakworks MCP server's config (engine/README.md, "The frakworks server in a
# Smith"): the engine hosts the server at $ENGINE_URL/mcp/frakworks, so nothing of it is in the image.
COPY frakworks-mcp-config.sh /usr/local/bin/frakworks-mcp-config
RUN chmod 755 /usr/local/bin/run-smith /usr/local/bin/git-askpass /usr/local/bin/meter-proxy.py /usr/local/bin/engine-proxy.py /usr/local/bin/fm-relay /usr/local/bin/check-result /usr/local/bin/frakworks-mcp-config
USER smith
WORKDIR /work
ENV HOME=/home/smith
# A human-gated MCP call (the ops server's `run`, which waits on the
# user's approval for up to ~1800 s + 30 s grace) must not be abandoned at
# Claude Code's ~60 s default: 35 minutes, in ms (engine/README.md, "Smith runs").
ENV MCP_TOOL_TIMEOUT=2100000
# Not the default GRADLE_USER_HOME: the gradle image declares it a VOLUME
# (see engine/Dockerfile); keep the cache in smith's home.
ENV GRADLE_USER_HOME=/home/smith/.gradle
# The engine's dependency cache, baked so --offline works: only the Gradle build
# files, from the `engine` build context (images.yml and compose.local.yaml pass it),
# and `gradle resolveDependencies` (engine/build.gradle.kts) over them, as engine/Dockerfile
# does. No engine sources. settings.gradle.kts includes ../sdk, which is outside that
# context: a stub build file stands in (the SDK's only dependency is the Kotlin plugin's,
# which every module resolves too). The build files are dropped after; the jars stay.
COPY --chown=smith:smith gradle.properties /home/smith/.gradle/gradle.properties
COPY --from=engine --chown=smith:smith settings.gradle.kts build.gradle.kts gradle.properties /tmp/deps/engine/
# Each module's build file, as engine/Dockerfile lists them (keep both in step with settings.gradle.kts).
COPY --from=engine --chown=smith:smith modules/common/build.gradle.kts /tmp/deps/engine/modules/common/
COPY --from=engine --chown=smith:smith modules/github/build.gradle.kts /tmp/deps/engine/modules/github/
COPY --from=engine --chown=smith:smith modules/events/build.gradle.kts /tmp/deps/engine/modules/events/
COPY --from=engine --chown=smith:smith modules/accounts/build.gradle.kts /tmp/deps/engine/modules/accounts/
COPY --from=engine --chown=smith:smith modules/jev/build.gradle.kts /tmp/deps/engine/modules/jev/
COPY --from=engine --chown=smith:smith modules/smith/build.gradle.kts /tmp/deps/engine/modules/smith/
COPY --from=engine --chown=smith:smith modules/incidents/build.gradle.kts /tmp/deps/engine/modules/incidents/
COPY --from=engine --chown=smith:smith modules/hitlp/build.gradle.kts /tmp/deps/engine/modules/hitlp/
COPY --from=engine --chown=smith:smith modules/firstmate/build.gradle.kts /tmp/deps/engine/modules/firstmate/
COPY --from=engine --chown=smith:smith modules/flow-intake/build.gradle.kts /tmp/deps/engine/modules/flow-intake/
COPY --from=engine --chown=smith:smith modules/flow-feature/build.gradle.kts /tmp/deps/engine/modules/flow-feature/
COPY --from=engine --chown=smith:smith modules/flow-delivery/build.gradle.kts /tmp/deps/engine/modules/flow-delivery/
COPY --from=engine --chown=smith:smith modules/flow-doctor/build.gradle.kts /tmp/deps/engine/modules/flow-doctor/
COPY --from=engine --chown=smith:smith modules/flow-friction/build.gradle.kts /tmp/deps/engine/modules/flow-friction/
COPY --from=engine --chown=smith:smith modules/flow-proposal/build.gradle.kts /tmp/deps/engine/modules/flow-proposal/
COPY --from=engine --chown=smith:smith modules/plugins/build.gradle.kts /tmp/deps/engine/modules/plugins/
COPY --from=engine --chown=smith:smith modules/api/build.gradle.kts /tmp/deps/engine/modules/api/
COPY --from=engine --chown=smith:smith modules/app/build.gradle.kts /tmp/deps/engine/modules/app/
RUN mkdir -p /tmp/deps/sdk && echo 'plugins { kotlin("jvm") }' > /tmp/deps/sdk/build.gradle.kts \
    && cd /tmp/deps/engine && gradle --no-daemon resolveDependencies \
    && rm -rf /tmp/deps /home/smith/.gradle/daemon /home/smith/.gradle/caches/build-cache-1
ENTRYPOINT ["run-smith"]
