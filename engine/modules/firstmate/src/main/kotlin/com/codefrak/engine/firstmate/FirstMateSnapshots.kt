package com.codefrak.engine.firstmate

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * What a frozen session's pod left (V76): the Claude session file, the working folder as a tar.gz (null when it was over
 * [FirstMateSnapshot.CAP_BYTES] and dropped) and the folder's size.
 */
class FirstMateSnapshot(val sessionFile: ByteArray?, val workdirTar: ByteArray?, val size: Long) {
    val dropped get() = workdirTar == null && size > CAP_BYTES

    companion object {
        /** The largest working folder a freeze keeps: 200 MB. */
        const val CAP_BYTES = 200L * 1024 * 1024
    }
}

/** `engine.first_mate_snapshot` (V76): one snapshot per frozen session, replaced at each freeze, deleted with the session. */
@Repository
class FirstMateSnapshots(private val jdbc: JdbcClient) {
    fun save(sessionId: String, snapshot: FirstMateSnapshot) {
        jdbc.sql("DELETE FROM engine.first_mate_snapshot WHERE session_id = :s").param("s", sessionId).update()
        jdbc.sql("INSERT INTO engine.first_mate_snapshot (session_id, session_file, workdir_tar, size) VALUES (:s, :f, :w, :z)")
            .param("s", sessionId).param("f", snapshot.sessionFile, java.sql.Types.BINARY).param("w", snapshot.workdirTar, java.sql.Types.BINARY).param("z", snapshot.size).update()
    }

    fun get(sessionId: String): FirstMateSnapshot? =
        jdbc.sql("SELECT session_file, workdir_tar, size FROM engine.first_mate_snapshot WHERE session_id = :s").param("s", sessionId)
            .query { rs, _ -> FirstMateSnapshot(rs.getBytes(1), rs.getBytes(2), rs.getLong(3)) }.optional().orElse(null)
}
