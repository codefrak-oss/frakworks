-- How often the engine scans a target repo on its own (engine/README.md, "When it scans"): in
-- minutes, from 1 to 1440 (a day), set with PATCH /repos/{owner}/{name} or the desktop's
-- Config > Repos. It replaces the engine-wide hourly scan property, which is gone: every
-- existing row is given 60, today's hourly scan carried over. No column default: a new row's
-- interval is written by POST /repos (60 unless the body gives one), never fallen back to.
ALTER TABLE engine.target_repo ADD COLUMN scan_interval_minutes INTEGER;
UPDATE engine.target_repo SET scan_interval_minutes = 60;
ALTER TABLE engine.target_repo ALTER COLUMN scan_interval_minutes SET NOT NULL;
ALTER TABLE engine.target_repo ADD CONSTRAINT target_repo_scan_interval CHECK (scan_interval_minutes BETWEEN 1 AND 1440);
