package com.codefrak.engine

import com.codefrak.engine.github.ReadFakes
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
@TestPropertySource(properties = ["engine.build.commit="])
class VersionControllerTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `reports the build version, and no login`() {
        mvc.get("/version").andExpect {
            status { isOk() }
            jsonPath("$.version") { value("0.0.1") }
            jsonPath("$.fleetLogin") { doesNotExist() }
        }
    }

    @Test
    fun `a build given no commit reports a null commit`() {
        mvc.get("/version").andExpect {
            status { isOk() }
            jsonPath("$.commit") { value(null as Any?) }
        }
    }
}

/** The image's ENGINE_BUILD_COMMIT (images.yml's GIT_SHA), through `engine.build.commit`, is `commit` on /version. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
@TestPropertySource(properties = ["engine.build.commit=0123456789abcdef0123456789abcdef01234567"])
class VersionCommitTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `reports the commit the image was built from`() {
        mvc.get("/version").andExpect {
            status { isOk() }
            jsonPath("$.commit") { value("0123456789abcdef0123456789abcdef01234567") }
            jsonPath("$.version") { value("0.0.1") }
        }
    }
}
