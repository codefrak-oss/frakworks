// engine-api (engine/README.md, "Engine modules"): what the engine serves over HTTP that no lower module owns, the
// board (`board/**`), the engine-served MCP servers (`mcp/**`), First mate's chats (`firstmate/**`), the Frakpanel
// tiles (`frakpanel/**`), target repos and access (`repos/**` controllers), IntakeController, FlowIssues and the
// root controllers (health, version, echo, process test). Depends on engine-plugins; never on the app.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencies {
    api(project(":engine-plugins"))
    // The hitlp server's store and its human path (engine/README.md, "The hitlp server"); engine-api's HitlpMcpServer
    // and GET /needs reach it only through HitlpTasksApi.
    api(project(":engine-hitlp"))
    // The first-mate sessions (engine/README.md, "First-mate sessions"): TokenController ends a revoked token's sessions.
    api(project(":engine-firstmate"))
    testImplementation(testFixtures(project(":engine-events")))
}
