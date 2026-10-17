package com.codefrak.engine.github

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** git push's stderr for #469 (incident 294b2672): a token without `workflow` scope, touching a workflow. */
const val WORKFLOW_REJECTION = """To https://github.com/codefrak-oss/frakworks.git
 ! [remote rejected] smith/issue-469 -> smith/issue-469 (refusing to allow a Personal Access Token to create or update workflow `.github/workflows/images.yml` without `workflow` scope)
error: failed to push some refs to 'https://github.com/codefrak-oss/frakworks.git'
"""

class PushRejectedTest {
    @Test
    fun `the workflow-scope rejection names the cause and the fix`() {
        val rejected = assertNotNull(PushRejected.parse(WORKFLOW_REJECTION))
        assertEquals("smith/issue-469", rejected.ref)
        assertEquals("refusing to allow a Personal Access Token to create or update workflow `.github/workflows/images.yml` without `workflow` scope", rejected.reason)
        assertEquals("push rejected: the token lacks the workflow scope for .github/workflows/images.yml; give the repo a token with workflow scope (PUT /repos/{owner}/{name}/token), then retry",
            rejected.message!!.lines().first())
        assertTrue(WORKFLOW_REJECTION.trim() in rejected.message!!, "git's own output is kept below the first line")
    }

    @Test
    fun `any other rejection gives git's reason, from the remote lines when the bracket names only a hook`() {
        val stderr = """remote: error: GH006: Protected branch update failed for refs/heads/smith/issue-5.
To https://github.com/acme/widgets.git
 ! [remote rejected] smith/issue-5 -> smith/issue-5 (protected branch hook declined)
error: failed to push some refs to 'https://github.com/acme/widgets.git'
"""
        val rejected = assertNotNull(PushRejected.parse(stderr))
        assertEquals("smith/issue-5", rejected.ref)
        assertEquals("push rejected: smith/issue-5: error: GH006: Protected branch update failed for refs/heads/smith/issue-5.", rejected.message!!.lines().first())

        val bracket = PushRejected.parse(" ! [remote rejected] smith/issue-5 -> smith/issue-5 (shallow update not allowed)\n")
        assertEquals("push rejected: smith/issue-5: shallow update not allowed", bracket!!.message!!.lines().first())
    }

    @Test
    fun `a push that fails without a remote rejection is not one`() {
        assertNull(PushRejected.parse("fatal: unable to access 'https://github.com/acme/widgets.git/': The requested URL returned error: 502\n"))
        assertNull(PushRejected.parse("To https://github.com/acme/widgets.git\n ! [rejected]        smith/issue-5 -> smith/issue-5 (non-fast-forward)\n"))
    }
}
