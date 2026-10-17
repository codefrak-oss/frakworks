-- The tier (1-10, engine.tier) the refactoring job's inspection run starts at (#1491; engine/README.md, "Refactoring jobs").
-- NULL: the job run's usual resolution. The filed tickets' own tier is not this.
ALTER TABLE engine.refactor_config ADD COLUMN tier INT CHECK (tier BETWEEN 1 AND 10);
