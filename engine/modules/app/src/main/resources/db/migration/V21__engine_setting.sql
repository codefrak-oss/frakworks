-- Engine settings kept in the database instead of static config, one row per
-- setting, editable through the API (engine/README.md, "Its own tables").
-- The first is the intake refiner's own tier (engine/README.md, "Intake"),
-- seeded with 5, what the Spring property it replaces held.
CREATE TABLE engine.engine_setting (
    name        VARCHAR(100)  PRIMARY KEY,   -- e.g. intake.tier
    setting     VARCHAR(2000) NOT NULL,      -- the value, as text
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO engine.engine_setting (name, setting) VALUES ('intake.tier', '5');
