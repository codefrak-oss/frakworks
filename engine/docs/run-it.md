# Run it

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

Alone, without the stack (no Smith runs):

No local JDK or Gradle is needed; the Dockerfile builds inside a Gradle image
(tests run as part of the build). Tests drive jobs by hand; no job executor runs in tests.

```
docker build --build-context root=. -t frakworks-engine engine
docker run --rm -p 8080:8080 frakworks-engine
curl 'http://localhost:8080/echo?message=hello'
```

There is no authentication on purpose; do not expose it beyond localhost.
<!-- test intake 2026-09-24T00:58Z-a: safe to delete -->
<!-- test intake 2026-09-24T03:33Z: safe to delete -->
<!-- test intake 2026-09-24T18:55Z: safe to delete -->
<!-- test intake 2026-09-24T18:41Z: safe to delete -->
<!-- test intake 2026-09-25T21:00Z: safe to delete -->
<!-- test intake 2026-10-05T12:43Z: safe to delete -->
<!-- test intake 2026-10-05T12:50Z: safe to delete -->
<!-- test intake 2026-10-07T01:21Z: safe to delete -->
