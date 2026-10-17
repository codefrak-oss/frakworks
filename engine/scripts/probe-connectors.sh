#!/bin/bash
# Which MCP servers does `claude -p` load under the current credential?
# With CLAUDE_CODE_OAUTH_TOKEN set (a `claude setup-token` token) expect only
# local servers; without it (keychain login) expect `claudeai` connectors too.
# Why: engine/README.md, "No claude.ai connectors in a Smith".
#   bash engine/scripts/probe-connectors.sh
#   set -a; source .env; set +a; bash engine/scripts/probe-connectors.sh
if [ -n "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then cred=setup-token; else cred=login; fi
echo "credential: $cred"
cd "$(mktemp -d)"
env -u CLAUDECODE -u CLAUDE_CODE_CHILD_SESSION -u CLAUDE_CODE_ENTRYPOINT \
  claude -p --output-format stream-json --verbose --max-turns 1 "say ok" </dev/null 2>&1 | python3 -c '
import json, sys
for line in sys.stdin:
    try: d = json.loads(line)
    except ValueError: continue
    if d.get("subtype") == "init":
        for s in d.get("mcp_servers", []): print(" ", s["source"], s["status"], s["name"])
'
