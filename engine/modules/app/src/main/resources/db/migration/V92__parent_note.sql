-- A live parent's notes for the next wave's parent session (#2596): note_for_next_parent writes one row at once, so a
-- parent killed mid-wave keeps what it noted; the next parent run of the same split (its feature instance) gets every
-- row in its brief, oldest first.
CREATE TABLE engine.parent_note (
    id        BIGSERIAL PRIMARY KEY,
    instance  TEXT        NOT NULL,
    repo      TEXT        NOT NULL,
    issue     BIGINT      NOT NULL,
    run_id    TEXT        NOT NULL,
    noted_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    note      TEXT        NOT NULL
);
CREATE INDEX parent_note_instance ON engine.parent_note (instance, id);
