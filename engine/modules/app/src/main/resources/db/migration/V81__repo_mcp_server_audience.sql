-- Who gets a repo MCP server (#2449; engine/README.md, "First mate's MCP servers", "A Smith's repo MCP servers"):
-- 'first-mate' (the desktop's and a first-mate pod's --mcp-config), 'smith' (a Smith run of the repo, in its sandbox),
-- or 'both'. Every row so far was a First mate's and stays one, so no First mate loses a server and no Smith gains one
-- here; the default is dropped afterwards so every new row says its audience.
ALTER TABLE engine.repo_mcp_server ADD COLUMN audience VARCHAR(10) NOT NULL DEFAULT 'first-mate'
    CHECK (audience IN ('smith', 'first-mate', 'both'));
ALTER TABLE engine.repo_mcp_server ALTER COLUMN audience DROP DEFAULT;
