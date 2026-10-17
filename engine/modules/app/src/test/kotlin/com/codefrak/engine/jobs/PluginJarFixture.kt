package com.codefrak.engine.jobs

import com.codefrak.fixtureplugin.FixturePlugin
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Builds a plugin jar for the tests from the `com.codefrak.fixtureplugin` classes compiled under `src/test`
 * ([FixturePlugin] and its beans), with a `Frak-Plugin-Package` manifest attribute unless [write] is told to leave it
 * out. The jar goes into a temp directory a test points `PluginJars.useDirectory` at, as `TarTest` does for its fixtures.
 */
object PluginJarFixture {
    const val PACKAGE = "com.codefrak.fixtureplugin"

    /** A fresh directory for a test's jars; the test deletes it after. */
    fun directory(): Path = Files.createTempDirectory("plugin-jars-")

    fun delete(dir: Path) { Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) } } }

    /**
     * Writes `dir/name` holding the package's classes; [pkg] is the manifest attribute's value, none when null;
     * [diagrams] are the fixture diagrams ([diagram]) packed as `bpmn/<name>.bpmn`, the shape a plugin's flows take.
     */
    fun write(dir: Path, name: String = "fixture.jar", pkg: String? = PACKAGE, diagrams: List<String> = emptyList()): Path {
        val classes = classFiles()
        check(classes.isNotEmpty()) { "no compiled classes of $PACKAGE on the test classpath" }
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            if (pkg != null) mainAttributes.putValue(PluginJars.PACKAGE_ATTRIBUTE, pkg)
        }
        val jar = dir.resolve(name)
        JarOutputStream(Files.newOutputStream(jar), manifest).use { out ->
            val prefix = PACKAGE.replace('.', '/') + "/"
            out.putNextEntry(JarEntry(prefix)); out.closeEntry()
            classes.forEach { file ->
                out.putNextEntry(JarEntry(prefix + file.name))
                Files.copy(file, out)
                out.closeEntry()
            }
            if (diagrams.isNotEmpty()) { out.putNextEntry(JarEntry("bpmn/")); out.closeEntry() }
            diagrams.forEach { d ->
                out.putNextEntry(JarEntry("bpmn/$d.bpmn"))
                out.write(diagram(d))
                out.closeEntry()
            }
        }
        return jar
    }

    /**
     * A fixture diagram's XML: `src/test/resources/plugin-flows/<name>.bpmn.xml`. The `.bpmn.xml` suffix keeps them off
     * the engine's classpath auto-deploy and out of `BpmnStyleTest`: they are deployed only from a jar, by `PluginFlows`.
     */
    fun diagram(name: String): ByteArray =
        PluginJarFixture::class.java.getResourceAsStream("/plugin-flows/$name.bpmn.xml")?.use { it.readBytes() } ?: error("no fixture diagram $name")

    /** The jar every flow test registers: the good flow, named `fixture.jar`. */
    const val FLOW = "fixture-flow"

    /** The `.class` files of the fixture package, from the directory the test classpath holds them in. */
    private fun classFiles(): List<Path> {
        val url = FixturePlugin::class.java.getResource("FixturePlugin.class") ?: error("FixturePlugin.class not on the classpath")
        check(url.protocol == "file") { "the fixture classes are not in a directory: $url" }
        return Path.of(url.toURI()).parent.listDirectoryEntries("*.class").filter { it.isRegularFile() }.sortedBy { it.name }
    }
}
