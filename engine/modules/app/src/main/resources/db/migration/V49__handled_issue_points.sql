-- A ticket's story points (bots/story-points.md): the refiner's estimate, from
-- the engine trailer's `points <n>`, recorded when the flow is claimed, as
-- asked_by (V47) is. Null on a ticket filed without one (a split child, a
-- hand-filed issue, one from before this column). Recorded and shown only.
ALTER TABLE engine.handled_issue ADD COLUMN points SMALLINT;
