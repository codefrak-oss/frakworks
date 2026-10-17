-- How long await_children waits for a split's child to change before it answers {changed: false} (#2571), in
-- seconds; set through PUT /settings/parent-await-cap.
INSERT INTO engine.engine_setting (name, setting) VALUES ('parent.await-cap-seconds', '900');
