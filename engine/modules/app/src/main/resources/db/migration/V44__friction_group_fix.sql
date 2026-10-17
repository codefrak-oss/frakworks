-- A friction group's fixed-by link (engine/README.md, "Friction notes", "Fixed by"): the one ticket that fixes the
-- group (`fix_repo`, `fix_issue`; a ticket may fix several groups), `fix_state` (`fix-filed` or `fixed`, null with no
-- link), the fixing PR's merge time once `fixed`, and `fix_recurred_at`, the first note after that merge. A link a
-- user cleared, or a ticket closed as not planned, leaves `fix_dismissed` (`owner/name#n`), so the sync does not
-- link that ticket again. The two-things bug's `bug_*` columns (V43) are a different thing and stay as they are.
ALTER TABLE engine.friction_group ADD COLUMN fix_repo VARCHAR(200);
ALTER TABLE engine.friction_group ADD COLUMN fix_issue BIGINT;
ALTER TABLE engine.friction_group ADD COLUMN fix_state VARCHAR(10);
ALTER TABLE engine.friction_group ADD COLUMN fix_merged_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE engine.friction_group ADD COLUMN fix_recurred_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE engine.friction_group ADD COLUMN fix_dismissed VARCHAR(220);
