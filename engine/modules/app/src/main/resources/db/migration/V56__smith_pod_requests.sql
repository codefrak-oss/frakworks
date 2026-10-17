-- Burstable Smith pods (engine/README.md, "Smith runs", "Hosting models"): a
-- Kubernetes Smith's memory and CPU requests, separate from its limits
-- (engine.smith.memory-bytes / engine.smith.cpus). Seeded 1 GiB and 0.5 CPU,
-- so a node fits several Smiths and the fleet's quota admits more at once; set
-- through PUT /settings/smith-hosting, never above the limit. Docker ignores them.
INSERT INTO engine.engine_setting (name, setting) VALUES ('smith.k8s.memory-request-bytes', '1073741824');
INSERT INTO engine.engine_setting (name, setting) VALUES ('smith.k8s.cpu-request', '0.5');

-- A run the cluster evicted or preempted (KubernetesSandbox.evicted): told apart
-- from a failure and an OOM kill. Null on rows from before, and where unknown.
ALTER TABLE engine.smith_run_stats ADD COLUMN evicted BOOLEAN;
