package com.codefrak.engine.smith

/** The first file in a tar archive (Docker's copy-from-container format): a 512-byte header, then the data. */
fun firstTarEntry(tar: ByteArray): ByteArray? {
    if (tar.size < 512) return null
    val size = String(tar, 124, 12, Charsets.US_ASCII).trim { it <= ' ' }.ifEmpty { return null }.toLong(8).toInt()
    return tar.copyOfRange(512, 512 + size)
}

/**
 * A ustar archive of `dir/` and the files in it, owned by the Smith image's
 * user (uid 65533): Docker's copy-to-container format. A file's mode is its
 * [modes] entry, else 0644; a nested file's parent directories get entries of
 * their own (0755, the same owner), so none is left to Docker to make as root.
 */
fun tarOf(dir: String, files: Map<String, ByteArray>, modes: Map<String, Int> = emptyMap()): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    fun header(name: String, size: Int, type: Char, mode: String) {
        val h = ByteArray(512)
        fun put(offset: Int, value: String) = value.toByteArray(Charsets.US_ASCII).copyInto(h, offset)
        put(0, name); put(100, mode); put(108, "0177775"); put(116, "0177775")
        put(124, "%011o".format(size)); put(136, "%011o".format(System.currentTimeMillis() / 1000))
        put(148, "        "); h[156] = type.code.toByte(); put(257, "ustar"); put(263, "00")
        val sum = h.sumOf { it.toInt() and 0xff }
        put(148, "%06o".format(sum)); h[154] = 0; h[155] = ' '.code.toByte()
        out.write(h)
    }
    val dirs = sortedSetOf("$dir/")
    files.keys.forEach { path ->
        var parent = path.substringBeforeLast('/', "")
        while (parent.length > dir.length && parent.startsWith("$dir/")) { dirs.add("$parent/"); parent = parent.substringBeforeLast('/', "") }
    }
    dirs.forEach { header(it, 0, '5', "0000755") }
    files.forEach { (path, data) ->
        header(path, data.size, '0', "%07o".format(modes[path] ?: 0b110_100_100))
        out.write(data)
        out.write(ByteArray((512 - data.size % 512) % 512))
    }
    out.write(ByteArray(1024))
    return out.toByteArray()
}

/** A regular file read out of a tar archive by [tarFiles]: its bytes and its permission bits. */
class TarFile(val data: ByteArray, val mode: Int)

/**
 * The regular files in a tar archive (Docker's copy-from-container format), by
 * their names in it: ustar names with their prefix, and a PAX `path` record's
 * name where one is given (Docker writes those for long names). Directories,
 * links and other entries are skipped.
 */
fun tarFiles(tar: ByteArray): Map<String, TarFile> = buildMap {
    fun field(at: Int, len: Int) = String(tar, at, len, Charsets.UTF_8).substringBefore('\u0000')
    fun octal(at: Int, len: Int) = field(at, len).trim().ifEmpty { "0" }.toLong(8)
    var pos = 0
    var paxPath: String? = null
    while (pos + 512 <= tar.size && tar[pos].toInt() != 0) {
        val size = octal(pos + 124, 12).toInt()
        val type = tar[pos + 156].toInt().toChar()
        val data = tar.copyOfRange(pos + 512, minOf(tar.size, pos + 512 + size))
        val prefix = if (field(pos + 257, 5) == "ustar") field(pos + 345, 155) else ""
        val name = paxPath ?: (if (prefix.isEmpty()) "" else "$prefix/") + field(pos, 100)
        paxPath = null
        when (type) {
            'x' -> paxPath = String(data, Charsets.UTF_8).lineSequence()
                .map { it.substringAfter(' ') }.firstOrNull { it.startsWith("path=") }?.removePrefix("path=")
            '0', '\u0000' -> put(name, TarFile(data, octal(pos + 100, 8).toInt() and 0b111_111_111))
        }
        pos += 512 + (size + 511) / 512 * 512
    }
}
