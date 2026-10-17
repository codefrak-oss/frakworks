-- A docs-first repo (#2339; engine/README.md, "Docs path"): its tickets default to the light docs path (kind chore,
-- a low tier, no design run). Off for every existing repo; set through PATCH /repos/{owner}/{name} { docsFirst }.
ALTER TABLE engine.target_repo ADD COLUMN docs_first BOOLEAN NOT NULL DEFAULT FALSE;
