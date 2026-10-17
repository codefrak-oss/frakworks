-- First-mate sessions freeze and resume (docs/proposals/iphone-first-mate.md; engine/README.md, "First-mate sessions"):
-- after 15 minutes without a message the engine saves the Claude session file and a tarball of the pod's working folder
-- (first_mate_snapshot), deletes the pod and marks the session `frozen`; the next message starts a new pod from them.
-- claude_session_id is the id `claude -p` reported in its init line, cwd the pod's working folder, workdir_dropped true
-- when the folder was over the 200 MB cap and was not kept. The optional expiry of frozen sessions is row
-- `firstmate.frozen-expiry-days` of engine.engine_setting: absent (off) unless set through PUT /settings/first-mate-expiry.
ALTER TABLE engine.first_mate_session ADD COLUMN claude_session_id VARCHAR(64);
ALTER TABLE engine.first_mate_session ADD COLUMN cwd TEXT;
ALTER TABLE engine.first_mate_session ADD COLUMN last_message_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE engine.first_mate_session ADD COLUMN frozen_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE engine.first_mate_session ADD COLUMN workdir_dropped BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE engine.first_mate_session SET last_message_at = created_at;
CREATE INDEX first_mate_session_state ON engine.first_mate_session (state, last_message_at);

CREATE TABLE engine.first_mate_snapshot (
    session_id   VARCHAR(36) PRIMARY KEY REFERENCES engine.first_mate_session (id) ON DELETE CASCADE,
    session_file BYTEA,                            -- ~/.claude/projects/<encoded cwd>/<claude_session_id>.jsonl
    workdir_tar  BYTEA,                            -- tar.gz of cwd; null when dropped (over 200 MB)
    size         BIGINT NOT NULL,                  -- the working folder's size in bytes when frozen
    at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
DELETE FROM engine.engine_setting WHERE name = 'firstmate.frozen-expiry-days';
