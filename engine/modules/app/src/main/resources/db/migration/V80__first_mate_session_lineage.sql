-- A phone first-mate session's HITLP lineage (engine/README.md, "Lineage"), captured once at POST /first-mate/sessions
-- and mounted into every pod the session gets, so a resumed pod is handed the same snapshot. Null on older sessions.
ALTER TABLE engine.first_mate_session ADD COLUMN lineage TEXT;
