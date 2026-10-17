package com.codefrak.engine.smith

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The brief's tar, read back by the real tar: the format Docker's copy-to-container takes. */
class TarTest {
    @Test
    fun `tarOf makes an archive tar extracts, with the directory`() {
        val dir = Files.createTempDirectory("tar-")
        val files = mapOf("in/brief.json" to """{"round":2}""".toByteArray(), "in/commits.bundle" to ByteArray(1500) { it.toByte() })
        val archive = dir.resolve("in.tar").also { Files.write(it, tarOf("in", files)) }
        val tar = ProcessBuilder("tar", "-xf", archive.toString(), "-C", dir.toString()).redirectErrorStream(true).start()
        assertEquals(0, tar.waitFor(), tar.inputStream.bufferedReader().readText())
        assertEquals("""{"round":2}""", Files.readString(dir.resolve("in/brief.json")))
        assertEquals(files.getValue("in/commits.bundle").toList(), Files.readAllBytes(dir.resolve("in/commits.bundle")).toList())
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `tarOf keeps a file's mode and makes its parent directories, and tarFiles reads back what tar wrote`() {
        val dir = Files.createTempDirectory("tar-")
        val files = mapOf("in/repro/x.sh" to "#!/bin/sh\necho hi\n".toByteArray(), "in/repro/lib/a.so" to ByteArray(700) { it.toByte() })
        val archive = dir.resolve("in.tar").also { Files.write(it, tarOf("in", files, mapOf("in/repro/x.sh" to 0b111_101_101))) }
        val untar = ProcessBuilder("tar", "-xf", archive.toString(), "-C", dir.toString()).redirectErrorStream(true).start()
        assertEquals(0, untar.waitFor(), untar.inputStream.bufferedReader().readText())
        assertTrue(Files.isExecutable(dir.resolve("in/repro/x.sh")), "the script stays executable")
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("in/repro/lib/a.so"))))
        assertEquals(files.getValue("in/repro/lib/a.so").toList(), Files.readAllBytes(dir.resolve("in/repro/lib/a.so")).toList())

        // What Docker's copy-from-container gives for a directory: a tar of it, under its own name, as tar writes one.
        val longName = "d".repeat(120)
        Files.createDirectories(dir.resolve("out/repro/$longName"))
        Files.writeString(dir.resolve("out/repro/$longName/f.txt"), "deep")
        val packed = dir.resolve("out.tar")
        val tar = ProcessBuilder("tar", "--format=pax", "-cf", packed.toString(), "-C", dir.resolve("out").toString(), "repro")
            .redirectErrorStream(true).start()
        assertEquals(0, tar.waitFor(), tar.inputStream.bufferedReader().readText())
        val read = tarFiles(Files.readAllBytes(packed))
        assertEquals(setOf("repro/$longName/f.txt"), read.keys)
        assertEquals("deep", String(read.getValue("repro/$longName/f.txt").data))
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `tarFiles reads tarOf's files with their modes, and skips directories`() {
        val read = tarFiles(tarOf("repro", mapOf("repro/x.sh" to byteArrayOf(1, 2), "repro/a/b" to byteArrayOf(3)), mapOf("repro/x.sh" to 0b111_101_101)))
        assertEquals(setOf("repro/x.sh", "repro/a/b"), read.keys)
        assertEquals(0b111_101_101, read.getValue("repro/x.sh").mode)
        assertEquals(0b110_100_100, read.getValue("repro/a/b").mode)
        assertEquals(listOf<Byte>(1, 2), read.getValue("repro/x.sh").data.toList())
    }

    @Test
    fun `inputsTar roots its entries under in, the INPUT_DIR's last segment`() {
        val read = tarFiles(inputsTar(mapOf("brief.json" to """{"round":0}""".toByteArray()), emptyMap()))
        assertEquals(setOf("in/brief.json"), read.keys)
        assertEquals("""{"round":0}""", String(read.getValue("in/brief.json").data))
    }
}
