-- The engine is no longer a HITLP surface (#100013, part 1 of #100011): the desktop and the phone talk to the HITLP
-- server directly, so the relay's homes go. Nothing is carried over: the surface URL, the per-login credentials and
-- the per-repo switch belonged only to the removed relay.
DROP TABLE engine.hitlp_surface_credential;
ALTER TABLE engine.hitlp_server DROP COLUMN surface_url;
ALTER TABLE engine.target_repo DROP COLUMN hitlp_surface;
