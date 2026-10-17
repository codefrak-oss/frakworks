# Research notes

What lands here: the answers to `research` tickets the asker chose to keep, and
measurements that ask for no decision (a token breakdown, a timing study, a
census of what Smiths did). One dated pair or file each, never revised: a later
answer or measurement is a new file.

A `research` ticket's answer is kept by the engine, not in git
(`engine/README.md`, "Research"): the ticket's Answer section shows the page,
and the ticket closes when the asker acks it. Answering `save` (or `save:
<directory>`, under `bots/research/` or `docs/`) makes the engine commit the
pair to the default branch as `<yyyy-mm-dd>-<slug>.md` (the record: the
question, the answer, the citations and the date) and `<yyyy-mm-dd>-<slug>.html`
(the same answer as one self-contained page, by `engine/smith/pitch-guide.md`),
the slug from the ticket's title, the commit message naming the ticket.
Nothing here has a status line or a decision log; a proposal
(`docs/proposals/`) is where decisions live (`bots/README.md`, "Research
tickets and proposals").
