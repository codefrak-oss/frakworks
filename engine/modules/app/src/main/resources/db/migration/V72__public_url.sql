-- The engine's public URL (#2253; engine/README.md, "The public URL"): the address users reach the engine and its web
-- version at, e.g. a Kubernetes Ingress host. Row `engine.public-url` of `engine.engine_setting`, absent (null) until
-- set through PUT /settings/public-url: the engine cannot know its Ingress host, and no property, environment variable
-- or config file holds it. Nothing is seeded; this only states that no row is there.
DELETE FROM engine.engine_setting WHERE name = 'engine.public-url';
