-- A child's question under a running live parent is asked of the parent first (#100049; engine/docs/feature.md,
-- "Children ask the parent"): asked_of names it (`#<parent>`) while the row waits on the parent, and is cleared when
-- the parent escalates it to the user, so the row then reads exactly as one asked of the user. Null for every row
-- asked of the user, as every row before this column was.
ALTER TABLE engine.intake_question ADD COLUMN asked_of VARCHAR(100);
