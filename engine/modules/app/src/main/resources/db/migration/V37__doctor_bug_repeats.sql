-- Repeat sightings, regressions after a fix (docs/proposals/doctor-to-bugs.md §3; engine/README.md, "Doctor").
-- repeat_level: the highest count of distinct stuck tickets the doctor acted on (comment at 2, priority at 3,
-- needs-review at 4), so each step is taken once. restarts: the reproduces the doctor restarted on a needs-repro
-- bug, at most 2. review_at: when it added needs-review (the bug's GET /needs sighting row). fix_running_at: the
-- engine's first start after a fixed bug's close, from which a where: engine sighting is a regression.
ALTER TABLE engine.doctor_bug ADD COLUMN repeat_level INT NOT NULL DEFAULT 0;
ALTER TABLE engine.doctor_bug ADD COLUMN restarts INT NOT NULL DEFAULT 0;
ALTER TABLE engine.doctor_bug ADD COLUMN review_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE engine.doctor_bug ADD COLUMN fix_running_at TIMESTAMP WITH TIME ZONE;
