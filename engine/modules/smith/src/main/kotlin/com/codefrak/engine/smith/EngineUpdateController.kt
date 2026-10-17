package com.codefrak.engine.smith

import com.codefrak.engine.engineJson
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** A registry build: the manifest's digest and the commit its config's `org.opencontainers.image.revision` names. */
data class EngineBuild(val digest: String?, val commit: String?, val source: String? = null)

/**
 * `GET /engine/update`'s answer. `mode` is `kubernetes` (the engine restarts its own Deployment: `selfUpdate` true)
 * or `host` (a Compose engine cannot replace its own container: the host does it, the desktop's pull and recreate).
 * `running` is GET /version's commit; `latest` the registry's newest `:main`; `behind` how many commits `latest` is
 * ahead of `running` on GitHub, null when unknown. `openSessions` counts first-mate sessions open or frozen.
 * `error` says why a part is unknown; nothing is guessed.
 */
data class EngineUpdateStatus(
    val mode: String,
    val selfUpdate: Boolean,
    val running: String?,
    val image: String?,
    val latest: EngineBuild?,
    val behind: Int?,
    val updateAvailable: Boolean,
    val openSessions: Int?,
    val error: String?,
    val checkedAt: Instant,
)

/** The image the engine's own Deployment runs, `registry/repo:tag`, read with the get the base's Role grants. */
fun interface EngineImage {
    fun image(namespace: String): String?
}

@Component
class KubernetesEngineImage : EngineImage {
    override fun image(namespace: String): String? =
        KubernetesClientBuilder().withConfig(Config.autoConfigure(null)).build().use { k ->
            k.apps().deployments().inNamespace(namespace).withName(EngineRestartController.DEPLOYMENT).get()
                ?.spec?.template?.spec?.containers?.firstOrNull { it.name == "engine" }?.image
        }
}

/** The registry's newest build of an image's tag. Anonymous: a public registry (GHCR) hands out a pull token. */
fun interface EngineRegistry {
    fun latest(image: String): EngineBuild
}

/** How many commits [head] is ahead of [base] in the GitHub repo [source] (`https://github.com/owner/name`). */
fun interface CommitsBehind {
    fun behind(source: String, base: String, head: String): Int?
}

/** First-mate sessions open or frozen, for the confirm. */
fun interface OpenSessions {
    fun count(): Int
}

@Component
class JdbcOpenSessions(private val jdbc: JdbcClient) : OpenSessions {
    override fun count(): Int =
        jdbc.sql("SELECT count(*) FROM engine.first_mate_session WHERE state IN ('open', 'frozen')").query(Int::class.javaObjectType).single()
}

/**
 * The desktop's one Deploy button (engine/README.md, "Deploying on Kubernetes"): the engine owns how an update is
 * done, the app owns when. `GET /engine/update` is the status, `POST /engine/update` the action: on Kubernetes the
 * same as `POST /engine/restart` ([EngineRestartController], with its `argoApp`); outside a cluster a 409 whose
 * reason says the host updates it (the desktop's `docker compose pull` and `up`). The status is kept [TTL], so the
 * desktop's 30 s poll asks the registry and GitHub at most once a minute.
 */
@RestController
class EngineUpdateController(
    private val inCluster: InCluster,
    private val restart: EngineRestartController,
    private val image: EngineImage,
    private val registry: EngineRegistry,
    private val commits: CommitsBehind,
    private val sessions: OpenSessions,
    @Value("\${engine.build.commit:}") commit: String,
    private val namespace: () -> String = { File(EngineRestartController.NAMESPACE_FILE).readText().trim() },
    private val now: () -> Instant = Instant::now,
) {
    @org.springframework.beans.factory.annotation.Autowired
    constructor(inCluster: InCluster, restart: EngineRestartController, image: EngineImage, registry: EngineRegistry,
                commits: CommitsBehind, sessions: OpenSessions, @Value("\${engine.build.commit:}") commit: String) :
        this(inCluster, restart, image, registry, commits, sessions, commit,
            { File(EngineRestartController.NAMESPACE_FILE).readText().trim() }, Instant::now)

    private val log = LoggerFactory.getLogger(EngineUpdateController::class.java)
    private val running: String? = commit.trim().ifEmpty { null }
    @Volatile private var cached: EngineUpdateStatus? = null

    @GetMapping("/engine/update")
    fun status(@RequestParam(required = false) fresh: Boolean = false): EngineUpdateStatus {
        val c = cached
        if (!fresh && c != null && c.checkedAt.plus(TTL).isAfter(now())) return c.copy(openSessions = openSessions())
        return read().also { cached = it }
    }

    @PostMapping("/engine/update")
    fun update(request: HttpServletRequest?, @RequestParam(required = false) argoApp: String? = null): EngineRestartResponse {
        if (!inCluster.inPod()) throw ResponseStatusException(HttpStatus.CONFLICT, HOST_NOTE)
        return restart.restart(request, argoApp).also { cached = null }
    }

    private fun openSessions(): Int? = runCatching { sessions.count() }.getOrNull()

    private fun read(): EngineUpdateStatus {
        val at = now()
        if (!inCluster.inPod()) return EngineUpdateStatus(HOST, false, running, null, null, null, false, openSessions(), HOST_NOTE, at)
        val errors = mutableListOf<String>()
        val ref = runCatching { image.image(namespace()) }.onFailure { errors += "engine Deployment unreadable: ${it.message}" }.getOrNull()
        val latest = ref?.let { r -> runCatching { registry.latest(r) }.onFailure { errors += "registry: ${it.message}" }.getOrNull() }
        val latestCommit = latest?.commit
        val source = latest?.source
        val run = running
        val behind = if (latestCommit != null && run != null && latestCommit == run) 0
            else if (latestCommit != null && run != null && source != null)
                runCatching { commits.behind(source, run, latestCommit) }.onFailure { errors += "GitHub compare: ${it.message}" }.getOrNull()
            else null
        if (running == null) errors += "this engine was built without a commit (ENGINE_BUILD_COMMIT)"
        val available = running != null && latest?.commit != null && latest.commit != running
        errors.forEach { log.info("engine update status: {}", it) }
        return EngineUpdateStatus(KUBERNETES, true, running, ref, latest, behind, available, openSessions(), errors.joinToString("; ").ifEmpty { null }, at)
    }

    companion object {
        const val KUBERNETES = "kubernetes"
        const val HOST = "host"
        const val HOST_NOTE = "the engine is not in a cluster and cannot replace its own container: its host updates it " +
            "(the desktop's docker compose pull and up)"
        val TTL: Duration = Duration.ofSeconds(60)
    }
}

/**
 * The OCI distribution API, anonymously: on a 401 the `WWW-Authenticate: Bearer` realm hands out a pull token.
 * It speaks https, and plain http to a registry that answers https with a TLS error (an `SSLException`: what a plain-HTTP
 * listener such as the LAN registry answers an https client with), Docker's rule for an `insecure-registries` entry. Any
 * other failure (a refused connection, a timeout, a 4xx or 5xx) is the error: a registry that speaks https is never downgraded.
 */
@Component
class OciEngineRegistry(private val send: (HttpRequest) -> HttpResponse<String>) : EngineRegistry {
    @org.springframework.beans.factory.annotation.Autowired
    constructor() : this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()
        .let { c -> { r: HttpRequest -> c.send(r, HttpResponse.BodyHandlers.ofString()) } })

    override fun latest(image: String): EngineBuild {
        val (host, repo) = parse(image)
        var scheme = "https"
        var token: String? = null
        fun get(path: String, accept: String): HttpResponse<String> {
            fun request(s: String) = HttpRequest.newBuilder(URI.create("$s://$host/v2/$repo$path")).timeout(Duration.ofSeconds(15)).header("Accept", accept)
                .apply { token?.let { header("Authorization", "Bearer $it") } }.GET().build()
            fun once(): HttpResponse<String> {
                if (scheme == "http") return send(request("http"))
                return try { send(request("https")) } catch (e: Exception) {
                    if (!tls(e)) throw e
                    scheme = "http"
                    try { send(request("http")) } catch (e2: Exception) { error("https: ${e.message ?: e}; http: ${e2.message ?: e2}") }
                }
            }
            var r = once()
            if (r.statusCode() == 401 && token == null) {
                token = pullToken(r.headers().firstValue("www-authenticate").orElse(null), repo)
                r = once()
            }
            if (r.statusCode() != 200) error("$host answered ${r.statusCode()} for $repo$path")
            return r
        }
        val index = get("/manifests/$TAG", ACCEPT)
        val digest = index.headers().firstValue("docker-content-digest").orElse(null)
        var manifest = engineJson.readTree(index.body())
        manifest.get("manifests")?.firstOrNull()?.get("digest")?.asString()?.let { manifest = engineJson.readTree(get("/manifests/$it", ACCEPT).body()) }
        val config = manifest.get("config")?.get("digest")?.asString() ?: return EngineBuild(digest, null)
        val labels = engineJson.readTree(get("/blobs/$config", "application/json, */*").body()).get("config")?.get("Labels")
        return EngineBuild(digest, labels?.get(REVISION)?.asString()?.ifBlank { null }, labels?.get(SOURCE)?.asString()?.ifBlank { null })
    }

    /** Whether a failure is TLS's: an `SSLException` (or a subclass), itself or as a cause. */
    private fun tls(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(8).any { it is javax.net.ssl.SSLException }

    private fun pullToken(challenge: String?, repo: String): String {
        val params = Regex("""(\w+)="([^"]*)"""").findAll(challenge ?: error("401 with no WWW-Authenticate")).associate { it.groupValues[1] to it.groupValues[2] }
        val realm = params["realm"] ?: error("no realm in WWW-Authenticate: $challenge")
        val q = listOfNotNull(params["service"]?.let { "service=${enc(it)}" }, "scope=${enc(params["scope"] ?: "repository:$repo:pull")}").joinToString("&")
        val r = send(HttpRequest.newBuilder(URI.create("$realm?$q")).timeout(Duration.ofSeconds(15)).GET().build())
        if (r.statusCode() != 200) error("token endpoint answered ${r.statusCode()}")
        val body = engineJson.readTree(r.body())
        return (body.get("token") ?: body.get("access_token"))?.asString() ?: error("token endpoint sent no token")
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    companion object {
        const val TAG = "main"
        const val REVISION = "org.opencontainers.image.revision"
        const val SOURCE = "org.opencontainers.image.source"
        val ACCEPT = listOf("application/vnd.oci.image.index.v1+json", "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.manifest.v1+json", "application/vnd.docker.distribution.manifest.v2+json").joinToString(", ")

        /** `ghcr.io/o/frakworks-engine:main` or `...@sha256:…` to (`ghcr.io`, `o/frakworks-engine`); a bare name is Docker Hub's. */
        fun parse(image: String): Pair<String, String> {
            val name = image.substringBefore("@").let { n -> if (n.substringAfterLast("/").contains(":")) n.substringBeforeLast(":") else n }
            val first = name.substringBefore("/")
            return if (name.contains("/") && (first.contains(".") || first.contains(":") || first == "localhost")) first to name.substringAfter("/")
            else "registry-1.docker.io" to (if (name.contains("/")) name else "library/$name")
        }
    }
}

/** GitHub's compare API, anonymously (the engine's source repo is public): `ahead_by` of head over base. */
@Component
class GitHubCommitsBehind(
    @Value("\${engine.github.api-url:https://api.github.com}") private val api: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : CommitsBehind {
    @org.springframework.beans.factory.annotation.Autowired
    constructor(@Value("\${engine.github.api-url:https://api.github.com}") api: String) :
        this(api, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build())

    override fun behind(source: String, base: String, head: String): Int? {
        val repo = Regex("""github\.com/([^/]+/[^/.]+)""").find(source)?.groupValues?.get(1) ?: return null
        val r = http.send(HttpRequest.newBuilder(URI.create("${api.trimEnd('/')}/repos/$repo/compare/$base...$head"))
            .timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json").GET().build(), HttpResponse.BodyHandlers.ofString())
        if (r.statusCode() != 200) error("GitHub answered ${r.statusCode()} for $repo compare")
        return engineJson.readTree(r.body()).get("ahead_by")?.asInt()
    }
}
