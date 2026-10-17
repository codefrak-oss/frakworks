-- Whether a repo's chores, bugs and features get a security review on each pushed round
-- (engine/README.md, "Chore"): editable from the desktop, on by default, so every existing
-- repo keeps its reviews. Off: `open/update pr` sets `securityReview` false and the flow goes
-- from the engine check straight to `review pr`.
ALTER TABLE engine.target_repo ADD COLUMN security_review BOOLEAN NOT NULL DEFAULT TRUE;
