-- A friction group's pending candidate fix (engine/README.md, "Friction notes", "Fixed by"): an intake that has not
-- filed its ticket yet (`fix_intake`, its process instance id, and `fix_intake_repo`, the repo it files in). The group
-- is `fix-pending` while it is set; when the intake files its ticket the link becomes a `fix_repo`/`fix_issue` link
-- (`fix-filed`) and these go back to null, as they do when the intake ends without a ticket. Never set beside a ticket link.
ALTER TABLE engine.friction_group ADD COLUMN fix_intake VARCHAR(64);
ALTER TABLE engine.friction_group ADD COLUMN fix_intake_repo VARCHAR(200);
CREATE INDEX friction_group_fix_intake ON engine.friction_group (fix_intake);
