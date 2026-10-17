-- First mate's per-repo MCP servers (#1801; engine/README.md, "First mate's MCP servers"): the servers the desktop adds
-- to a First mate's --mcp-config for this target repo, beside the engine's own two. headers and env are JSON objects,
-- name -> {"secret": false, "value": "..."} or {"secret": true, "sealed": "<base64>", "setAt": "..."}; a sealed value
-- is SecretsKey's, with "<repo>#mcp/<server>/<key>" as associated data, and is never answered by a read.
CREATE TABLE engine.repo_mcp_server (
    repo       VARCHAR(200)             NOT NULL REFERENCES engine.target_repo (full_name) ON DELETE CASCADE,
    name       VARCHAR(64)              NOT NULL,
    kind       VARCHAR(10)              NOT NULL CHECK (kind IN ('http', 'sse', 'stdio')),
    url        TEXT,
    command    TEXT,
    args       TEXT                     NOT NULL DEFAULT '[]',
    headers    TEXT                     NOT NULL DEFAULT '{}',
    env        TEXT                     NOT NULL DEFAULT '{}',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(100),
    PRIMARY KEY (repo, name)
);
