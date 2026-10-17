-- Private documents (#1711; engine/README.md, "Private documents"): design docs and specs the user keeps out of a
-- target repo's git history, which a Smith on that repo reads through the frakworks MCP server. Never written to GitHub.
CREATE TABLE engine.repo_doc (
    repo       VARCHAR(200)             NOT NULL REFERENCES engine.target_repo (full_name) ON DELETE CASCADE,
    name       VARCHAR(100)             NOT NULL,
    content    TEXT                     NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(100)             NOT NULL,
    PRIMARY KEY (repo, name)
);
