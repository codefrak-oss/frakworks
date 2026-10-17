-- A repo's child-work mode (#2501, part of #2499; engine/README.md, "Split features"): how a design run cuts a
-- feature's split and how its children are worked. `serial` (today's behaviour) for every existing repo; set through
-- PATCH /repos/{owner}/{name} { childMode }. `parallel` and `live` change the design prompt now; their flow behaviour
-- lands in later parts of #2499.
ALTER TABLE engine.target_repo ADD COLUMN child_mode TEXT NOT NULL DEFAULT 'serial'
    CONSTRAINT target_repo_child_mode_check CHECK (child_mode IN ('serial', 'parallel', 'live'));
