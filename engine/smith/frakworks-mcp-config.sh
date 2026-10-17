#!/usr/bin/env bash
# Prints the --mcp-config run-smith.sh hands claude (engine/README.md, "The
# frakworks server in a Smith"): the engine's two MCP servers over streamable
# HTTP at ENGINE_URL, frakworks (engine/README.md, "The frakworks server in a
# Smith") and frakworks-engine-diagnostics ("The diagnostics server"), with no header and no credential: the engine knows the
# run by the sandbox's address and serves it the Smith role. In a run, ENGINE_URL
# is engine-proxy.py's (run-smith.sh), which rides out a moment with no ready engine.
#
# Then the repo's own servers (#2449; engine/README.md, "A Smith's repo MCP
# servers"), when the engine put any in SMITH_MCP_SERVERS: one JSON object,
# name to --mcp-config entry, headers already opened and X-Frakworks-Run set to
# this run's id, from the run's Secret. They are merged under the engine's
# two, so an engine name always wins (the engine already dropped such a row).
set -euo pipefail
: "${ENGINE_URL:?ENGINE_URL is required}" "${SMITH_ID:?SMITH_ID is required}"
jq -n --arg url "${ENGINE_URL%/}" --argjson repo "${SMITH_MCP_SERVERS:-{\}}" \
  '{mcpServers: ($repo + {frakworks: {type: "http", url: ($url + "/mcp/frakworks")},
   "frakworks-engine-diagnostics": {type: "http", url: ($url + "/mcp/frakworks-engine-diagnostics")}})}'
