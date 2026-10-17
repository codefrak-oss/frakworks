-- A repo's engine check (engine/README.md, "Chore": `wait for the engine check`),
-- editable from the desktop: the check run its pushed branches wait for, and the
-- path prefixes that make a branch need it, comma-separated. Null ci_check: the
-- repo falls back to engine.ci-check.repos. Null or empty ci_paths: every path.
ALTER TABLE engine.target_repo ADD COLUMN ci_check VARCHAR(200);
ALTER TABLE engine.target_repo ADD COLUMN ci_paths VARCHAR(2000);
