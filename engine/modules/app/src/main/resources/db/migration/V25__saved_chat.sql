-- First mate chats the user saved for later from the desktop (engine/README.md,
-- "Saved First mate chats"), so a second desktop on the same engine lists and
-- restores them. The id is a UUID the desktop mints and keeps on the live
-- conversation, so saving the same conversation again updates its row.
-- messages is the strip's own history, a JSON array of {role, text, at,
-- images?}, at most 200 entries, stored as the user wrote it (no redaction).
CREATE TABLE engine.saved_chat (
    id                 VARCHAR(64)   PRIMARY KEY,
    title              VARCHAR(200)  NOT NULL,
    started_at         TIMESTAMP WITH TIME ZONE,    -- the first message's time
    saved_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    repo               VARCHAR(200),                -- null means All repos
    tier               INT,
    claude_session_id  VARCHAR(100),                -- resumes only on the machine that has it
    saved_from         VARCHAR(200),                -- the desktop's host name, display only
    turns              INT           NOT NULL,      -- user messages
    message_count      INT           NOT NULL,
    messages           TEXT          NOT NULL
);
CREATE INDEX saved_chat_saved_at ON engine.saved_chat (saved_at);
