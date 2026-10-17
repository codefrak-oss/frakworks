-- GitHub publish of work items (#2575, part 2 of #2567; engine/docs/work-items.md, "Sources"): a repo opts in to a
-- one-way issue copy of each of its work items. Off for every repo, existing and new; set through
-- PATCH /repos/{owner}/{name} { publishWorkItems }. The engine writes the copy; nothing on it is read back.
ALTER TABLE engine.target_repo ADD COLUMN publish_work_items BOOLEAN NOT NULL DEFAULT false;

-- The published copy's reference (owner/name#n), null until the item is first published.
ALTER TABLE engine.work_item ADD COLUMN published_ref TEXT;
