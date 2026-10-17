// engine-github-api (engine/README.md, "Engine modules"): the GitHub contract every module above programs against,
// with no client behind it: the interfaces (IssueDetails, GitHubPulls, GitPush, RepoContents, RepoSetupGitHub,
// GitHubCompare, RepoTokens, TokenIdentity, GitHubCallMeter, Tickets, TargetRepos, RepoMcpServers, RepoDocs,
// AnswerAttachments, DeploymentPatterns), their value types (IssueSnapshot, IssueDetails' shapes, TargetRepo,
// WorkItem, SetupReport, ...) and the pure helpers (issueUrl, splitRepo, DocsOnly, TokenScopes, DeploymentValueScan).
// The clients and the stores that implement them are engine-github's, which only engine-app depends on: a change in
// a client rebuilds and retests the github module and the app, not every module in between.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
    // FakeTickets (src/testFixtures): the in-memory Tickets the flow and app tests end tickets through.
    `java-test-fixtures`
}

dependencies {
    api(project(":engine-common"))
}
