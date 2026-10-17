-- Engine work items (#2568, part 1 of #2567; engine/docs/work-items.md): a ticket as an engine record instead of a
-- GitHub issue. Additive: nothing on the GitHub path reads or writes these tables yet.
CREATE TABLE engine.work_item (
    id          TEXT PRIMARY KEY,
    full_name   TEXT NOT NULL,
    description TEXT NOT NULL,
    kind        TEXT NOT NULL CONSTRAINT work_item_kind_check CHECK (kind IN ('feature', 'bug', 'chore')),
    tier        INTEGER CONSTRAINT work_item_tier_check CHECK (tier BETWEEN 1 AND 10),
    mode        TEXT,
    state       TEXT NOT NULL CONSTRAINT work_item_state_check CHECK (state IN ('blocked', 'ready', 'running', 'done', 'cancelled')),
    -- Where the item came from: the user's ask, an import from an outside tracker, a split's child, work a Smith raised.
    source_kind TEXT NOT NULL CONSTRAINT work_item_source_kind_check CHECK (source_kind IN ('ask', 'import', 'split-child', 'raised')),
    -- The source's own reference (an intake id, owner/name#n of an imported issue, the parent item's id).
    source_ref  TEXT,
    -- False for text from outside (an imported issue): later parts render it fenced, never as instructions.
    trusted     BOOLEAN NOT NULL,
    -- The GitHub issue a Smith run of this item works, while runs are still started from issues.
    ticket      BIGINT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX work_item_repo_idx ON engine.work_item (full_name, created_at);

-- Blocked by: [item] does not start until [blocker] is done.
CREATE TABLE engine.work_item_dependency (
    item    TEXT NOT NULL REFERENCES engine.work_item (id) ON DELETE CASCADE,
    blocker TEXT NOT NULL REFERENCES engine.work_item (id) ON DELETE CASCADE,
    PRIMARY KEY (item, blocker),
    CONSTRAINT work_item_dependency_self_check CHECK (item <> blocker)
);
CREATE INDEX work_item_dependency_blocker_idx ON engine.work_item_dependency (blocker);

-- An item's conversation, decisions, history and raised work, oldest first.
CREATE TABLE engine.work_item_entry (
    id         TEXT PRIMARY KEY,
    item       TEXT NOT NULL REFERENCES engine.work_item (id) ON DELETE CASCADE,
    entry_kind TEXT NOT NULL CONSTRAINT work_item_entry_kind_check CHECK (entry_kind IN ('conversation', 'decision', 'history', 'raised')),
    author     TEXT NOT NULL,
    body       TEXT NOT NULL,
    trusted    BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX work_item_entry_item_idx ON engine.work_item_entry (item, created_at);
