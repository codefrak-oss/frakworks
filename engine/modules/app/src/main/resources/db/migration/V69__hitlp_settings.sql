-- HITLP caps and retention (docs/proposals/hitlp-support.md, option A, part 2 of 3; engine/README.md, "The hitlp
-- server"): the longest a human.ask and a human.approve may wait (R6; a longer deadline is shortened to the cap, and
-- an escalation extends a task's ttl by it once), and how long a terminal task is kept for tasks/get before HitlpTick
-- deletes it (§7.5). Engine settings in the database, read and written through GET/PUT /settings/hitlp and the
-- desktop's Config > frakworks > HITLP: no property, environment variable or config file holds them.
INSERT INTO engine.engine_setting (name, setting) VALUES ('hitlp.ask-max-ttl-seconds', '604800');
INSERT INTO engine.engine_setting (name, setting) VALUES ('hitlp.approve-max-ttl-seconds', '86400');
INSERT INTO engine.engine_setting (name, setting) VALUES ('hitlp.retention-days', '30');
