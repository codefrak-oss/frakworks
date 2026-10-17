-- An optional address per AI account, for the user's reference only: the engine never reads it (engine/README.md, "AI accounts").
ALTER TABLE engine.ai_account ADD COLUMN email VARCHAR(200);
