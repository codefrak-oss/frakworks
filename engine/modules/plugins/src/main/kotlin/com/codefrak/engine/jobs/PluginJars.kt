package com.codefrak.engine.jobs

import com.codefrak.engine.bpmn.PluginFlowRules
import com.codefrak.engine.sdk.PluginDoctors
import com.codefrak.sdk.Doctor
import com.codefrak.sdk.FrakJob
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.io.support.ResourcePatternResolver
import org.springframework.stereotype.Component
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile

/** A plugin jar could not be loaded, or does not hold the named `FrakJob`; the message is the registrar's 400 reason. */
class PluginLoadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The plugin loader (engine/README.md, "Custom jobs and the SDK"): a jar of [directory] is loaded once per file name
 * and sha-256 into a [URLClassLoader] whose parent is the engine's, and an [AnnotationConfigApplicationContext]
 * whose parent is the [SdkContext], scanning the package the jar's `Frak-Plugin-Package` manifest attribute names.
 * Jobs on the same jar share the entry; the last job's [release] closes it. Every refusal is a [PluginLoadException]
 * with the reason, and closes what the call opened, so a refused registration leaves nothing behind.
 */
@Component
class PluginJars(private val sdk: SdkContext, private val doctors: PluginDoctors) {
    private val log = LoggerFactory.getLogger(PluginJars::class.java)

    /** One loaded jar: its name, file and sha-256, loader, context, and the jobs holding it. */
    class LoadedJar(val name: String, val file: Path, val sha256: String, val loader: URLClassLoader, val context: AnnotationConfigApplicationContext) {
        val jobIds = mutableSetOf<String>()
        val doctorNames = mutableListOf<String>()

        /** The jar's diagrams, `bpmn/<name>.bpmn` entries by entry name, read from the file; an unreadable jar is a [PluginLoadException]. */
        fun diagrams(): Map<String, ByteArray> = try {
            JarFile(file.toFile()).use { jar ->
                jar.entries().asSequence().filter { !it.isDirectory && DIAGRAM_ENTRY.matches(it.name) }.sortedBy { it.name }
                    .associate { entry -> entry.name to jar.getInputStream(entry).use { it.readBytes() } }
            }
        } catch (e: java.io.IOException) {
            throw PluginLoadException("jar $name cannot be read: ${e.message}", e)
        }

        /** What a diagram's names and classes bind to: this jar's own beans and classes, against [engine]'s (`PluginFlowRules.pluginProblems`). */
        fun binding(engine: org.springframework.context.ApplicationContext): PluginFlowRules.Binding = object : PluginFlowRules.Binding {
            override fun inPlugin(name: String) = context.containsLocalBean(name)
            override fun classInPlugin(fqcn: String) = runCatching { Class.forName(fqcn, false, loader).classLoader === loader }.getOrDefault(false)
            override fun inEngine(name: String) = engine.containsBean(name)
            override fun classInEngine(fqcn: String) = runCatching { Class.forName(fqcn, false, PluginJars::class.java.classLoader); true }.getOrDefault(false)
        }
    }

    private var directory: Path = Path.of(DIRECTORY)
    private val loaded = mutableMapOf<String, LoadedJar>()

    /** Test-only: the directory the jars are read from, in place of [DIRECTORY]. There is no setting for it. */
    fun useDirectory(dir: Path) { directory = dir }

    /** The names of the jars loaded now. */
    @Synchronized fun loaded(): List<String> = loaded.keys.sorted()

    /** The entry a job holds, or null; for the tests' sharing assertions. */
    @Synchronized fun entryOf(jobId: String): LoadedJar? = loaded.values.firstOrNull { jobId in it.jobIds }

    /**
     * The [FrakJob] named [bean] in [jar], loaded if it is not yet, with [jobId] holding the entry from now on.
     * A [PluginLoadException] names the reason: a bad name, a missing file, a missing manifest attribute, a context
     * that fails to refresh, a missing bean, or one that is not a `FrakJob`. An entry opened by this call is closed
     * on failure.
     */
    @Synchronized fun acquire(jar: String, bean: String, jobId: String): FrakJob {
        val (entry, fresh) = obtain(jar)
        try {
            val job = entry.context.getBean(bean)
            if (job !is FrakJob) throw PluginLoadException("bean $bean of $jar is a ${job::class.java.name}, not a FrakJob")
            entry.jobIds += jobId
            return job
        } catch (e: org.springframework.beans.factory.NoSuchBeanDefinitionException) {
            if (fresh) close(entry)
            throw PluginLoadException("jar $jar has no bean $bean")
        } catch (e: PluginLoadException) {
            if (fresh) close(entry)
            throw e
        } catch (e: org.springframework.beans.BeansException) {
            if (fresh) close(entry)
            throw PluginLoadException("bean $bean of $jar failed to load: ${rootCause(e).message ?: rootCause(e)::class.java.simpleName}", e)
        }
    }

    /**
     * The jar loaded (or shared) with no bean looked up, [jobId] holding the entry from now on: a flow job's hold
     * (`PluginFlows`, `PrepareFlowJobDelegate`, `PluginBeanResolver`). The same refusals as [acquire] up to the bean.
     */
    @Synchronized fun acquireContext(jar: String, jobId: String): LoadedJar {
        val (entry, _) = obtain(jar)
        entry.jobIds += jobId
        return entry
    }

    /** The entry by name and sha: loaded, shared, or reloaded when the file changed and no job holds the old one; `fresh` says this call opened it. */
    private fun obtain(jar: String): Pair<LoadedJar, Boolean> {
        checkName(jar)
        val file = directory.resolve(jar)
        if (!Files.isRegularFile(file)) throw PluginLoadException("jar $jar is not in $DIRECTORY")
        val sha = sha256(file)
        val existing = loaded[jar]
        val entry = when {
            existing == null -> load(jar, file, sha)
            existing.sha256 == sha -> existing
            existing.jobIds.isEmpty() -> { close(existing); load(jar, file, sha) }
            else -> {
                log.warn("jar {} changed on disk (sha {}); the loaded {} stays until its {} job(s) are gone", jar, sha, existing.sha256, existing.jobIds.size)
                existing
            }
        }
        return entry to (entry !== existing)
    }

    /** The job no longer holds its jar; the last job's release closes the context and loader. */
    @Synchronized fun release(jobId: String) {
        val entry = loaded.values.firstOrNull { jobId in it.jobIds } ?: return
        entry.jobIds -= jobId
        if (entry.jobIds.isEmpty()) close(entry)
    }

    /** Closes every loaded jar: at engine stop, and the tests' reset. */
    @PreDestroy @Synchronized fun closeAll() = loaded.values.toList().forEach { close(it) }

    private fun load(jar: String, file: Path, sha: String): LoadedJar {
        val attribute = try { JarFile(file.toFile()).use { it.manifest?.mainAttributes?.getValue(PACKAGE_ATTRIBUTE) } } catch (e: java.io.IOException) {
            throw PluginLoadException("jar $jar cannot be read: ${e.message}", e)
        }
        val pkg = attribute?.trim()?.takeIf { it.isNotEmpty() } ?: throw PluginLoadException("jar $jar has no $PACKAGE_ATTRIBUTE manifest attribute")
        val loader = URLClassLoader("plugin-$jar", arrayOf(file.toUri().toURL()), PluginJars::class.java.classLoader)
        val context = object : AnnotationConfigApplicationContext() {
            /**
             * The scan must not go through the JDK's `JarURLConnection` cache: it keeps one `JarFile` per jar URL open
             * for the life of the JVM, nothing here can close it, and a jar rewritten under the same name (a new sha)
             * would be read through the stale handle. With caches off, Spring opens the jar for the scan and closes it.
             */
            override fun getResourcePatternResolver(): ResourcePatternResolver =
                PathMatchingResourcePatternResolver(this).apply { setUseCaches(false) }
        }
        try {
            context.setDisplayName("plugin-$jar")
            context.setParent(sdk.context)
            context.setClassLoader(loader)
            context.scan(pkg)
            context.refresh()
        } catch (e: Exception) {
            runCatching { context.close() }; runCatching { loader.close() }
            throw PluginLoadException("jar $jar failed to load: ${rootCause(e).message ?: rootCause(e)::class.java.simpleName}", e)
        }
        val entry = LoadedJar(jar, file, sha, loader, context)
        context.beanFactory.getBeansOfType(Doctor::class.java).forEach { (name, doctor) ->
            val key = "$jar:$name"
            doctors.register(key, doctor); entry.doctorNames += key
        }
        loaded[jar] = entry
        log.info("jar {} ({}) loaded: package {}, {} doctor(s)", jar, sha, pkg, entry.doctorNames.size)
        return entry
    }

    private fun close(entry: LoadedJar) {
        entry.doctorNames.forEach { doctors.unregister(it) }
        runCatching { entry.context.close() }.onFailure { log.warn("closing the context of {}: {}", entry.name, it.message) }
        runCatching { entry.loader.close() }.onFailure { log.warn("closing the loader of {}: {}", entry.name, it.message) }
        if (loaded[entry.name] === entry) loaded.remove(entry.name)
        log.info("jar {} ({}) closed", entry.name, entry.sha256)
    }

    private fun rootCause(e: Throwable): Throwable = generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.last()

    companion object {
        /** Where the jars are, inside the engine container: the `engine-jobs` volume of engine/compose.local.yaml. */
        const val DIRECTORY = "/var/lib/frakworks/jobs"
        /** The manifest attribute naming the package to scan. */
        const val PACKAGE_ATTRIBUTE = "Frak-Plugin-Package"
        /** Where a jar's diagrams are: `bpmn/<name>.bpmn` entries, no deeper. */
        val DIAGRAM_ENTRY = Regex("""^bpmn/[^/]+\.bpmn$""")
        const val NAME_REASON = "config.jar is a file name in $DIRECTORY, not a path"

        /** A jar is a file name in the directory, never a path: an [IllegalArgumentException] with [NAME_REASON] otherwise. */
        fun checkName(jar: String) {
            require(jar.isNotBlank() && '/' !in jar && '\\' !in jar && jar != "." && jar != ".." && !jar.contains("..")
                && Path.of(jar).fileName?.toString() == jar) { NAME_REASON }
        }

        fun sha256(file: Path): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
    }
}
