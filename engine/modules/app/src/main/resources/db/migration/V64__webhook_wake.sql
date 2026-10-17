-- A webhook wake handed to the lease holder (#1802; engine/README.md, "Replicas and the lease"): a follower that gets a
-- GitHub delivery does not scan, it writes this one row, and the holder takes it (deletes it) within a second and wakes.
CREATE TABLE engine.webhook_wake (
    id           INTEGER                  PRIMARY KEY,  -- always 1: one pending wake at most, as a wake reads all of GitHub
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL
);
