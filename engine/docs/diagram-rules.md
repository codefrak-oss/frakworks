# Diagram rules

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

The rules are code: `com.codefrak.engine.bpmn.FlowRules` in main
(`FlowRules.problems(model, plane)`, the five below, each also on its own:
`ids`, `lowercase`, `drawn`, `crossings`, `labels`). `BpmnStyleTest` calls it on
every deployed diagram of the repo, so the build fails on a diagram that breaks
one (it keeps `allowedCrossings`); it runs without Spring and takes well under a
second. `PluginFlows` calls it on a plugin jar's diagrams when a flow job is
registered, together with `FlowRules.pluginProblems` ("Custom jobs and the SDK",
"Flow jobs": no scripts, delegates and listeners bound in the jar and never the
engine, call activities only to `callable-smith` with named variables), so a jar
that breaks a rule is refused with every problem listed.

- **Typed kebab-case ids.** Every task, event, gateway, sequence flow,
  message and error has a lowercase kebab-case id: its type, then what it
  does. `task-create-issue`, `gateway-outcome`, `flow-refine-outcome`. The
  prefix follows the type: `task-` (service, send, script), `user-task-`,
  `receive-`, `call-`, `subprocess-`, `start-`, `end-`, `event-` (intermediate), `gateway-`,
  `flow-`, `message-`, `error-`. This also rules out Modeler's generated ids
  such as `Activity_12t9mf8`. Set the id in the Modeler properties panel.
- **Lowercase everywhere.** Every name (process, task, event, gateway,
  flow label, message, error) and every annotation's text is lowercase:
  `answer the smith's question`, `pr opened`, not `Answer the Smith's question`.
- **Drawn as it runs.** Every element has a shape and every flow a line, and
  each line starts on its source's shape and ends on its target's. The engine
  ignores the drawing, so a flow can run correctly while the picture shows it
  going nowhere.
- **Labels sit off the lines.** Every named gateway, event and flow keeps
  its label's position in the file (a `BPMNLabel` with bounds), and no
  label's box touches a flow. Without a stored position the Modeler places
  the label itself, often on the line; drag it clear and save.
- **No crossing flows.** A crossing that cannot be drawn away goes in the
  test's `allowedCrossings`, with the reason.
