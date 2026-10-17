# Process migration

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

Operaton never moves a running instance to a newer definition by itself, so
without help every BPMN change strands in-flight flows on the version they
started on (#758 hit ENGINE-02004 on a gateway its old version had no branch
for). `ProcessMigrator` fixes that at startup:

- **When**: first among the `ApplicationReadyEvent` listeners
  (`@Order(Ordered.HIGHEST_PRECEDENCE)`), synchronously. The BPMN is already
  deployed (the process engine bean deploys it), and the job executor is not
  started yet: Operaton starts it from its own, unordered `ApplicationReadyEvent`
  listener, as do the startup scans. So no job runs against an old definition
  after boot. (`PostDeployEvent` is published only for an
  `@EnableProcessApplication` app, which the engine is not.)
- **What**: for every key, one query for its running instances, children of
  `smith-run` included; each (old version, latest version) pair with
  instances on it gets `createMigrationPlan(old, new).mapEqualActivities()
  .updateEventTriggers()` and a synchronous `execute()`. Instances with an open
  incident move too; the incident stays open, and a retry runs on the new
  definition. Old definitions are never deleted. One INFO line per key says
  what moved from which version, or "nothing stale".
- **Skip rule**: a plan or instance that fails validation (the instance waits
  at an activity the new version removed or renamed) is logged at WARN with
  the key, the version pair and Operaton's report, and those instances stay
  on their version. Any other failure is a WARN too. Startup never fails
  because of migration.
- **Job deployments are skipped**: a definition whose latest version is in a
  `job:<id>` deployment (a flow job's diagrams, "Custom jobs and the SDK") is
  never migrated; one INFO line names the keys ignored. Those flows are the
  job's to version, by delete and re-register.

**For BPMN authors**: a PR that removes or renames an activity a running
instance can be waiting at must add an explicit mapping for that version pair
to `ProcessMigrator.explicitMappings` (old activity id to new). Without one,
those instances stay on the old version by design.
