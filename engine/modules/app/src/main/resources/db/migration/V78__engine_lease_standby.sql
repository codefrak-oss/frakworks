-- The rollout standby (engine/README.md, "Replicas and the lease"; docs/proposals/leader-only-traffic.md): a replica
-- that does not hold the lease writes its name and the time on each heartbeat, so the holder's preStop
-- (GET /lease/await-standby) waits until a warm standby is there before it stops and frees the row.
ALTER TABLE engine.engine_lease ADD COLUMN standby VARCHAR(253);
ALTER TABLE engine.engine_lease ADD COLUMN standby_at TIMESTAMP WITH TIME ZONE;
