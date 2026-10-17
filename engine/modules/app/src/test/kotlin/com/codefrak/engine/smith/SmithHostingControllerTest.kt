package com.codefrak.engine.smith

import com.codefrak.engine.github.ReadFakes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import kotlin.test.assertEquals

/** `GET`/`PUT /settings/smith-hosting` over MockMvc, against the rows V51 seeds. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class SmithHostingControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: SmithHosting
    @Autowired lateinit var events: com.codefrak.engine.events.EngineEvents

    // The H2 is shared with the other Spring tests: leave the seeded rows behind.
    @AfterEach
    fun reset() { store.set(SmithHostingSettings("docker", "", "", "")) }

    private fun put(body: String) = mvc.put("/settings/smith-hosting") { contentType = MediaType.APPLICATION_JSON; content = body }

    @Test
    fun `GET answers docker and blank kubernetes settings, as seeded`() {
        mvc.get("/settings/smith-hosting").andExpect {
            status { isOk() }
            jsonPath("$.hosting") { value("docker") }
            jsonPath("$.namespace") { value("") }
        }
        assertEquals(HostingModel.DOCKER, store.model())
    }

    @Test
    fun `PUT sets the kubernetes settings, then the model`() {
        put("""{"namespace": "frakworks-smith", "image": "registry.example:5000/frakworks-smith:main", "engineUrl": "http://192.0.2.10:8090"}""")
            .andExpect { status { isOk() }; jsonPath("$.hosting") { value("docker") } }
        put("""{"hosting": "kubernetes"}""").andExpect { status { isOk() }; jsonPath("$.namespace") { value("frakworks-smith") } }
        assertEquals(HostingModel.KUBERNETES, store.model())
        put("""{"hosting": "docker"}""").andExpect { status { isOk() } }
        assertEquals(HostingModel.DOCKER, store.model())
    }

    @Test
    fun `PUT refuses a bad value and changes nothing`() {
        put("""{"hosting": "kubernetes"}""").andExpect { status { isBadRequest() } }
        put("""{"hosting": "nomad"}""").andExpect { status { isBadRequest() } }
        put("""{"namespace": "Not_A_Label"}""").andExpect { status { isBadRequest() } }
        put("""{"engineUrl": "ftp://x"}""").andExpect { status { isBadRequest() } }
        put("""{"kubeconfig": "/x"}""").andExpect { status { isBadRequest() } }
        put("""{"hosting": 1}""").andExpect { status { isBadRequest() } }
        assertEquals(SmithHostingSettings("docker", "", "", ""), store.get())
    }

    @Test
    fun `GET answers the seeded pod requests, PUT sets them, and refuses one above its limit naming the field`() {
        mvc.get("/settings/smith-hosting").andExpect {
            status { isOk() }
            jsonPath("$.memoryRequestBytes") { value(1073741824) }
            jsonPath("$.cpuRequest") { value(0.5) }
        }
        put("""{"memoryRequestBytes": 2147483648, "cpuRequest": 1}""").andExpect { status { isOk() } }
        assertEquals(2147483648L, store.get().memoryRequestBytes)
        assertEquals(1.0, store.get().cpuRequest)
        mvc.get("/settings/smith-hosting").andExpect { jsonPath("$.cpuRequest") { value(1.0) } }
        put("""{"memoryRequestBytes": 99999999999}""").andExpect {
            status { isBadRequest() }; status { reason(org.hamcrest.Matchers.containsString("memoryRequestBytes")) }
        }
        put("""{"cpuRequest": 64}""").andExpect {
            status { isBadRequest() }; status { reason(org.hamcrest.Matchers.containsString("cpuRequest")) }
        }
        put("""{"cpuRequest": "1"}""").andExpect { status { isBadRequest() } }
        assertEquals(2147483648L, store.get().memoryRequestBytes)
        assertEquals(1.0, store.get().cpuRequest)
    }

    @Test
    fun `in a pod docker is refused with a 400 saying so, outside one it is accepted`() {
        store.set(SmithHostingSettings("kubernetes", "frakworks-smith", "frakworks-smith:main", "http://engine.frakworks.svc:8080"))
        val token = java.io.File.createTempFile("token", "").apply { deleteOnExit() }
        val inPod = SmithHostingController(store, events, InCluster(token))
        val e = org.junit.jupiter.api.assertThrows<org.springframework.web.server.ResponseStatusException> {
            inPod.set(mapOf("hosting" to "docker"))
        }
        assertEquals(400, e.statusCode.value())
        assertEquals(InCluster.NO_DOCKER, e.reason)
        assertEquals(HostingModel.KUBERNETES, store.model())
        put("""{"hosting": "docker"}""").andExpect { status { isOk() } }
        assertEquals(HostingModel.DOCKER, store.model())
    }
}
