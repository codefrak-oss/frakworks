// engine-github (engine/README.md, "Engine modules"): the GitHub clients (RestGitHub, RestGitHubPulls, CliGitPush,
// RestRepoContents, RestRepoSetupGitHub, RestGitHubCompare), the token identity and permissions read from GitHub, and
// the stores behind the contract (StoredTargetRepos, StoredRepoMcpServers, StoredRepoDocs, StoredAnswerAttachments,
// StoredDeploymentPatterns). Everything it implements is declared in engine-github-api; only engine-app depends on
// this module, so the implementations are on the classpath at runtime and no other module recompiles for a change here.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencies {
    api(project(":engine-github-api"))
}
