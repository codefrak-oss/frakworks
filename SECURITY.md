# Security policy

## Reporting a vulnerability

Report vulnerabilities privately through GitHub's private vulnerability
reporting: the repository's **Security** tab, **Report a vulnerability**. Do
not open a public issue, pull request or discussion for a security problem:
the engine works this repo's issues as tickets, so a public issue is read and
acted on by autonomous agents.

Include what is affected, how to reproduce it, and the impact you see. You get
an acknowledgement on the advisory; a fix is developed in a private fork of the
advisory and disclosed once released.

## Scope

- The engine (`engine/`): its HTTP API, MCP servers, the Smith runs it starts
  and the credentials it stores (AI account credentials in its sealed store,
  GitHub tokens).
- The Smith sandbox (`engine/smith/`, `deploy/k8s/`): escapes, network egress,
  access to another run's data or secrets.
- The desktop app (`observability/desktop/`) and the iOS app (`ios/`):
  Electron/renderer isolation, rendering of untrusted Markdown, deep links.
- Prompt injection that makes an agent act outside its ticket (write to
  GitHub, leak a secret, reach a host it should not).

Out of scope: vulnerabilities in third-party dependencies with no exploit path
here (report them upstream), and deployments you do not own.

## Why it matters here

frakworks runs autonomous agents that hold GitHub tokens able to push, comment
and merge, and AI account credentials that cost money. A flaw that lets
untrusted input (an issue body, a comment, a fetched page) steer an agent, or
that exposes those credentials, has real-world effects without a human in the
loop. Treat reports accordingly, and never include live secrets in a report.
