-- The size of the run's ticket's Allowed scope (issue #975): how many paths and globs it grants
-- (AllowedScope.grantCount), the scope signal of the stage estimates (engine/README.md, "Stage
-- estimates"). Null for a run with no approved ticket (an intake's refiner) and for every row written
-- before this column: there is no backfill, and a null never falls in a scope bucket.
ALTER TABLE engine.smith_run_stats ADD COLUMN scope_paths INTEGER;
