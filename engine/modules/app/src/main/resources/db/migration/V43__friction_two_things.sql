-- Friction notes that carry two things (docs/proposals/friction-signatures.md §7, "The data"; engine/README.md,
-- "Friction notes", "Two things"). A note's `two_things` is Jev's `noul` value when it marked the note (null when
-- unmarked), `two_things_call_id` the call that did. A group's `bug_*` columns are the one bug the engine files per
-- group asking why: `bug_state` is `filing`, `open` or `closed`, null while none has been filed. A repo's
-- `friction_bugs` is the switch that lets the engine file one, off for every repo: no row is set true.
ALTER TABLE engine.friction_note ADD COLUMN two_things DOUBLE PRECISION;
ALTER TABLE engine.friction_note ADD COLUMN two_things_call_id VARCHAR(64);
ALTER TABLE engine.friction_group ADD COLUMN bug_repo VARCHAR(200);
ALTER TABLE engine.friction_group ADD COLUMN bug_issue BIGINT;
ALTER TABLE engine.friction_group ADD COLUMN bug_state VARCHAR(10);
ALTER TABLE engine.friction_group ADD COLUMN bug_intake_id VARCHAR(64);
ALTER TABLE engine.target_repo ADD COLUMN friction_bugs BOOLEAN NOT NULL DEFAULT FALSE;
