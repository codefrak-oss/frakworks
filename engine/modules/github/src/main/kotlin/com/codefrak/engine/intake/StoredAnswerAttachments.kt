package com.codefrak.engine.intake

import com.codefrak.engine.intake.AnswerAttachments.Companion.MAX_FLOW_BYTES
import com.codefrak.engine.intake.AnswerAttachments.Companion.check
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** [AnswerAttachments] in `engine.answer_attachment`. */
@Repository
class StoredAnswerAttachments(private val jdbc: JdbcClient) : AnswerAttachments {
    override fun list(flow: String): List<AttachmentInfo> =
        jdbc.sql("SELECT name, mime, size, created_at, created_by FROM engine.answer_attachment WHERE flow = :f ORDER BY name")
            .param("f", flow)
            .query { rs, _ -> AttachmentInfo(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getObject(4, OffsetDateTime::class.java), rs.getString(5)) }.list()

    override fun read(flow: String, name: String): Pair<String, ByteArray>? =
        jdbc.sql("SELECT mime, content FROM engine.answer_attachment WHERE flow = :f AND name = :n").param("f", flow).param("n", name)
            .query { rs, _ -> rs.getString(1) to rs.getBytes(2) }.optional().orElse(null)

    override fun save(flow: String, name: String, mime: String?, bytes: ByteArray, by: String): AttachmentInfo {
        val type = check(name, mime, bytes)
        val others = list(flow).filter { it.name != name }.sumOf { it.size.toLong() }
        if (others + bytes.size > MAX_FLOW_BYTES)
            throw AttachmentRefused("the flow's attachments would be ${others + bytes.size} bytes: at most $MAX_FLOW_BYTES (5 MiB) per flow", tooLarge = true)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        jdbc.sql("DELETE FROM engine.answer_attachment WHERE flow = :f AND name = :n").param("f", flow).param("n", name).update()
        jdbc.sql("INSERT INTO engine.answer_attachment (flow, name, mime, size, content, created_at, created_by) VALUES (:f, :n, :m, :s, :c, :t, :b)")
            .param("f", flow).param("n", name).param("m", type).param("s", bytes.size).param("c", bytes).param("t", now).param("b", by).update()
        return AttachmentInfo(name, type, bytes.size, now, by)
    }
}
