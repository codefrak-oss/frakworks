-- The first-mate pod relay protocol, engine side (docs/proposals/leader-only-traffic.md, Part 2; engine/README.md,
-- "First-mate sessions"): a session pod pulls the phone's messages from /first-mate/pod/{id}/inbox and acks them
-- (delivered_seq, monotonic), and posts claude's stdout lines to /first-mate/pod/{id}/outbox, numbered out_seq from 1
-- per pod lifetime (pod_epoch, bumped at each pod start). client_id makes a phone's retried message post idempotent.
-- A first-mate-pod token (engine_token.purpose) is scoped to one session by engine_token.session_id.
-- The unique indexes are plain, not partial: NULLs never collide in Postgres or H2, so they hold only for rows that
-- carry the columns, and the test build's H2 has no partial indexes. client_id and session_id are VARCHAR, not TEXT and
-- uuid: H2 cannot index a CLOB, and first_mate_session.id is VARCHAR(36).
ALTER TABLE engine.first_mate_session ADD COLUMN delivered_seq BIGINT NOT NULL DEFAULT 0;
ALTER TABLE engine.first_mate_session ADD COLUMN pod_epoch INT NOT NULL DEFAULT 0;
ALTER TABLE engine.first_mate_message ADD COLUMN pod_epoch INT;
ALTER TABLE engine.first_mate_message ADD COLUMN out_seq BIGINT;
ALTER TABLE engine.first_mate_message ADD COLUMN client_id VARCHAR(128);
CREATE UNIQUE INDEX first_mate_message_out ON engine.first_mate_message (session_id, pod_epoch, out_seq);
CREATE UNIQUE INDEX first_mate_message_client ON engine.first_mate_message (session_id, client_id);
ALTER TABLE engine.engine_token ADD COLUMN session_id VARCHAR(36);
