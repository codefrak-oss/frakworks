# Refactoring values

What this repo's code is moved toward, one small ticket at a time. A
refactoring job (`engine/README.md`, "Refactoring jobs") hands this text to a
Smith that inspects the code against it and proposes at most a few changes,
each capped in size and explained in its ticket's Context by the values it
serves. This is the shipped default; a repo stores its own text through
`PUT /repos/{owner}/{name}/refactor` (`values`) and returns to this one with
`values: null`.

Read it as a reader inspecting code, not as a style guide to enforce: a
proposal is worth filing when it moves the code clearly toward one of these
and costs little, not when it ticks a box.

## The values

- **Small files with one responsibility.** A file or class does one thing
  its name says; something that has grown two jobs is split along the seam
  that is already there.
- **Dependencies point one way.** The engine is layered, as `engine/README.md`
  ("Engine modules", the layer rule) records it, lowest first: `common`,
  `github`, `events`, `accounts`, `jev`, `smith` and `incidents` (layers 0-2),
  the flow modules (intake, feature, delivery, doctor, friction, proposal: one
  layer), then `plugins`, `api` and `app`. A package imports only from its own
  layer or below, and a flow never from another flow. Two packages that import
  each other are one package or a missing interface: merge them when they are
  one concern, or put the interface in the lower layer and register the
  implementation from the upper one. A hub that every package imports and that
  imports every package back is the first thing to cut.
- **A boundary is a build unit, not a folder.** A layer that holds is a Gradle
  module, so an upward import fails to compile rather than waiting for a
  reviewer. A package whose imports already respect the layers is a candidate
  to become one; the proposal names the module, its layer and the one
  interface (if any) the cut needs. A refactoring proposal makes the interface
  cut (a `CallerIdentity`, a `BoardRows`), never the module creation itself,
  which stays a ticket a person files.
- **Names that say what.** A function, variable or file is named for what it
  is or does, in the words the repo's own docs use; a name that needs a
  comment to decode is renamed.
- **No dead code.** Unused functions, parameters, flags, branches and files
  go; so do comments that describe what the code no longer does.
- **No duplication.** The same logic in two places becomes one, where the
  two really are the same thing and not two things that look alike today.
- **Tests beside behaviour.** Behaviour that matters has a test next to it;
  a change that makes code testable (an injected dependency, a pure function
  pulled out of a side effect) is a refactoring.
- **The repo's own conventions first.** `CLAUDE.md`, `AGENTS.md`, the READMEs
  and the surrounding code set the style; a proposal follows them even where
  these values would read otherwise, and never introduces a pattern the repo
  does not already have.
- **Behaviour unchanged.** A refactoring changes structure, never what the
  code does; anything that would change an output, an API answer or a stored
  value is a feature or a bug, not a proposal here.

## The checklist a proposal answers

- Which value does it serve, and where in the code (paths) did you see the
  gap?
- Is it one PR-sized change inside the size cap, with nothing left half-done
  if it is the only one merged?
- Does it keep behaviour, and how would a reviewer see that (which tests
  cover it, or which to add)?
- Does it follow the repo's conventions, or does it introduce something new?
- Which layer does each touched package sit in afterwards, and does any
  import point upward? Name the interface that keeps it pointing down.
