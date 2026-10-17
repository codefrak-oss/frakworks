-- The tier a first-mate session's pod runs at (engine/README.md, "First-mate sessions"): given on POST /first-mate/sessions
-- by the desktop, read at every pod start (a resume included). Null (the phone, and every older session): the pod
-- runs at engine.firstmate.tier.
ALTER TABLE engine.first_mate_session ADD COLUMN tier INT;
