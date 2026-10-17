rootProject.name = "engine"
// The plugin API (../sdk, engine/README.md "Custom jobs and the SDK"), built with the engine; the
// Dockerfile copies it to /sdk from the root build context so the path holds inside the image too.
include(":sdk")
project(":sdk").projectDir = file("../sdk")
// The engine's modules (engine/modules, engine/README.md "Engine modules"), in layer order: libraries engine-app
// depends on. The root project holds no code.
for (module in listOf("common", "github-api", "github", "events", "accounts", "jev", "smith-api", "smith", "incidents", "hitlp", "firstmate")) {
    include(":engine-$module")
    project(":engine-$module").projectDir = file("modules/$module")
}
// The flow modules (engine/README.md, "Engine modules"): each depends on the modules above, never on another flow.
for (flow in listOf("intake", "feature", "delivery", "doctor", "friction", "proposal")) {
    include(":engine-flow-$flow")
    project(":engine-flow-$flow").projectDir = file("modules/flow-$flow")
}
// The upper layers (engine/README.md, "Engine modules"): plugins over the flows, api over plugins, and the one
// application over everything.
for (module in listOf("plugins", "api", "app")) {
    include(":engine-$module")
    project(":engine-$module").projectDir = file("modules/$module")
}
// The build cache: local (the /gradle-cache mount in Docker), plus the cluster's shared Gradle cache node when
// GRADLE_REMOTE_CACHE_URL is set (engine/Dockerfile's build args, set by CI), so every buildkitd pod restores the
// same task outputs. Unset, as on a laptop, the build has no remote cache.
buildCache {
    local { isEnabled = true }
    System.getenv("GRADLE_REMOTE_CACHE_URL")?.takeIf { it.isNotBlank() }?.let { cacheUrl ->
        remote<HttpBuildCache> {
            url = uri(cacheUrl)
            isPush = System.getenv("GRADLE_REMOTE_CACHE_PUSH") == "true"
            isAllowInsecureProtocol = true
        }
    }
}
