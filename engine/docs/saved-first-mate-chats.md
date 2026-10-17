# Saved First mate chats

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

The desktop's First mate strip can save its live conversation for later
(`observability/desktop/README.md`, "Saved chats"), and any desktop pointed at
this engine lists the saved chats and can restore one into its own strip. The
engine only stores them, in `engine.saved_chat` ("Its own tables", migration
`V25__saved_chat.sql`), over four unauthenticated endpoints like the rest of
the API: every desktop sees every saved chat.

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" localhost:8090/saved-chats                                       # list, newest first, no transcripts
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" localhost:8090/saved-chats/<uuid>                                # one, with messages
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPUT localhost:8090/saved-chats/<uuid> -H 'content-type: application/json' \
  -d '{"title":"Why is the build host hot?","startedAt":"2026-09-27T10:00:00Z","messages":[{"role":"user","text":"Why is the build host hot?","at":1790000000000}]}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XDELETE localhost:8090/saved-chats/<uuid>
```

The id is minted by the desktop and kept on the conversation, so saving the
same conversation again updates its row (200) rather than adding one (201).
The transcript is kept as the user wrote it, with no redaction: whatever was
pasted into First mate is in the engine's database. A body is capped at 200
messages (the strip's own history cap) and 1 MiB. `claudeSessionId` only
resumes on the machine whose `~/.claude/projects/` holds it; the desktop
decides that, not the engine.
