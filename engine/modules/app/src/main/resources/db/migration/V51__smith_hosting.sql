-- Where the engine runs a Smith (engine/README.md, "Smith runs", "Hosting
-- models"), engine-wide, in engine.engine_setting (V21): `docker` (a sibling
-- container, what every run was before) or `kubernetes` (a Job on the k3s
-- cluster). Seeded `docker`, so nothing changes on deploy. The Kubernetes
-- model's own rows start blank: the namespace its Jobs run in, the Smith image
-- as the cluster pulls it, and the engine's URL as a pod reaches it. Set
-- through PUT /settings/smith-hosting; the model cannot be switched to
-- `kubernetes` while any of them is blank.
INSERT INTO engine.engine_setting (name, setting) VALUES ('smith.hosting', 'docker');
INSERT INTO engine.engine_setting (name, setting) VALUES ('smith.k8s.namespace', '');
INSERT INTO engine.engine_setting (name, setting) VALUES ('smith.k8s.image', '');
INSERT INTO engine.engine_setting (name, setting) VALUES ('smith.k8s.engine-url', '');
