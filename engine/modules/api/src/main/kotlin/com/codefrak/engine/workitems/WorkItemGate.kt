package com.codefrak.engine.workitems

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Dependencies gate a start (engine/docs/work-items.md): an item starts only when every
 * blocker is `done`; when one finishes, each dependent whose last blocker that was moves from `blocked` to `ready`.
 */
@Component
class WorkItemGate(private val store: WorkItemStore) {
    /** Whether [id] may start now: `ready` (or `blocked`, a stale state) with every blocker done. */
    fun startable(id: String): Boolean {
        val item = store.get(id) ?: return false
        if (item.state != WorkItem.READY && item.state != WorkItem.BLOCKED) return false
        return item.blockedBy.all { store.get(it)?.state == WorkItem.DONE }
    }

    /** Starts [id] (`running`), or false, leaving it as it is, while a blocker is not done. */
    @Transactional
    fun start(id: String): Boolean {
        if (!startable(id)) return false
        store.setState(id, WorkItem.RUNNING, "started")
        return true
    }

    /** Marks [id] done and promotes the dependents it was the last open blocker of; answers their ids. */
    @Transactional
    fun finish(id: String): List<String> {
        store.get(id) ?: throw WorkItemRefused("no work item $id")
        store.setState(id, WorkItem.DONE, "finished")
        return store.dependents(id).filter { dep ->
            store.get(dep)?.state == WorkItem.BLOCKED && startable(dep)
        }.onEach { store.setState(it, WorkItem.READY, "its last blocker $id is done") }
    }
}
