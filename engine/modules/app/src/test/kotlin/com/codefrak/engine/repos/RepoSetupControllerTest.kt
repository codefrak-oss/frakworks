package com.codefrak.engine.repos

import com.codefrak.engine.github.FakeRepoSetupGitHub
import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.github.RepoHook
import com.codefrak.engine.github.RepoSettings
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

/** `/repos/{o}/{n}/setup` over MockMvc, with [ReadFakes]' GitHub; the same context as [TargetRepoControllerTest]. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class RepoSetupControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var github: FakeRepoSetupGitHub

    private val repo = "acme/setupctl"

    @BeforeEach
    fun reset() {
        github.reset()
        repos.remove(repo)
        repos.add(repo)
        repos.setToken(repo, "github_pat_setupctl")
    }

    @Test
    fun `GET reports the setup and writes nothing`() {
        github.repos[repo] = RepoSettings("trunk", push = true, allowSquashMerge = true)
        github.labels[repo] = mutableListOf("bug", "chore")
        mvc.get("/repos/Acme/SetupCtl/setup").andExpect {
            status { isOk() }
            jsonPath("$.repo") { value(repo) }
            jsonPath("$.hasToken") { value(true) }
            jsonPath("$.readable") { value(true) }
            jsonPath("$.push") { value(true) }
            jsonPath("$.missingLabels[0]") { value("authorized") }
            jsonPath("$.defaultBranch.name") { value("trunk") }
            jsonPath("$.defaultBranch.requiresReviews") { value(false) }
            jsonPath("$.webhook.status") { value("unknown") }
        }
        assertEquals(emptyList(), github.writes)
    }

    @Test
    fun `hooks show only their events and whether they are active, never a payload URL`() {
        github.repos[repo] = RepoSettings("main", push = true, allowSquashMerge = true)
        github.hooks[repo] = listOf(RepoHook(listOf("issues", "check_suite", "issue_comment", "pull_request", "push"), true))
        mvc.get("/repos/$repo/setup").andExpect {
            status { isOk() }
            jsonPath("$.webhook.status") { value("present") }
            jsonPath("$.webhook.hooks[0].active") { value(true) }
            jsonPath("$.webhook.hooks[0].events[1]") { value("check_suite") }
            jsonPath("$.webhook.hooks[0].url") { doesNotExist() }
            jsonPath("$.webhook.hooks[0].config") { doesNotExist() }
        }
    }

    @Test
    fun `POST labels creates the missing ones, and nothing the second time`() {
        github.repos[repo] = RepoSettings("main", push = true, allowSquashMerge = true)
        github.labels[repo] = mutableListOf("bug", "chore", "feature", "feature:auto", "feature:confirm", "proposal", "research")
        mvc.post("/repos/$repo/setup/labels").andExpect {
            status { isOk() }
            jsonPath("$.created.length()") { value(2) }
            jsonPath("$.created[0]") { value("authorized") }
            jsonPath("$.created[1]") { value("intake") }
        }
        mvc.post("/repos/$repo/setup/labels").andExpect {
            status { isOk() }
            jsonPath("$.created.length()") { value(0) }
        }
        assertEquals(2, github.writes.size)
    }

    @Test
    fun `POST labels on a repo the token cannot read is 409`() {
        mvc.post("/repos/$repo/setup/labels").andExpect { status { isConflict() } }
        assertEquals(emptyList(), github.writes)
    }

    @Test
    fun `a repo without a token reports that, and reads nothing`() {
        github.repos[repo] = RepoSettings("main", push = true, allowSquashMerge = true)
        repos.clearToken(repo)
        mvc.get("/repos/$repo/setup").andExpect {
            status { isOk() }
            jsonPath("$.hasToken") { value(false) }
            jsonPath("$.readable") { value(false) }
        }
        mvc.post("/repos/$repo/setup/labels").andExpect { status { isConflict() } }
        assertEquals(emptyList(), github.writes)
    }

    @Test
    fun `a repo that is not a target repo is 404, with no GitHub call`() {
        github.repos["acme/nowhere"] = RepoSettings("main", push = true, allowSquashMerge = true)
        mvc.get("/repos/acme/nowhere/setup").andExpect { status { isNotFound() } }
        mvc.post("/repos/acme/nowhere/setup/labels").andExpect { status { isNotFound() } }
        assertEquals(emptyList(), github.writes)
    }
}
