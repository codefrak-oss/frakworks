-- Answer attachments (#1730; engine/README.md, "Answer attachments"): files the user attaches to an answer in the
-- desktop's question box, kept against the root flow that asked. The flow's Smith reads them through the frakworks MCP
-- server (list_attachments, read_attachment). Never written to GitHub, never to a pod's filesystem.
CREATE TABLE engine.answer_attachment (
    flow       VARCHAR(64)              NOT NULL,
    name       VARCHAR(100)             NOT NULL,
    mime       VARCHAR(100)             NOT NULL,
    size       INTEGER                  NOT NULL,
    content    BYTEA                    NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(100)             NOT NULL,
    PRIMARY KEY (flow, name)
);
