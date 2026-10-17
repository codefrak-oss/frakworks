-- Friction notes: the login of a user's correction (docs/proposals/friction-signatures.md §6; engine/README.md,
-- "Friction notes"). `grouped_by` says `user` for a note a login pulled out of its group; `grouped_login` says which.
-- Null on every existing row: no correction has been made before this column existed.
ALTER TABLE engine.friction_note ADD COLUMN grouped_login VARCHAR(100);
