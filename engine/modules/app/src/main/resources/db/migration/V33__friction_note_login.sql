-- Who noted it, for a note First mate made (issue #968): the GitHub login from the caller's
-- X-Frakworks-Login header, with source first-mate. Null on every Smith and retro row, and on
-- every row noted before this column: a Smith's note is stamped from its run, not a login.
ALTER TABLE engine.friction_note ADD COLUMN login VARCHAR(100);
