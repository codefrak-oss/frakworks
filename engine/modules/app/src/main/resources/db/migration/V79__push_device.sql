-- The iPhone first mate's push devices (engine/README.md, "Push notifications"): one row per APNs device token, bound
-- to the login and the first-mate token that registered it. Revoking that token deletes its rows (TokenController);
-- the foreign key covers a token row that is deleted outright.
CREATE TABLE engine.push_device (
    device_token VARCHAR(200) PRIMARY KEY,
    login        VARCHAR(39) NOT NULL,
    token_id     VARCHAR(36) NOT NULL REFERENCES engine.engine_token (id) ON DELETE CASCADE,
    platform     VARCHAR(16) NOT NULL,
    environment  VARCHAR(16) NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX push_device_login ON engine.push_device (login);
CREATE INDEX push_device_token_id ON engine.push_device (token_id);
