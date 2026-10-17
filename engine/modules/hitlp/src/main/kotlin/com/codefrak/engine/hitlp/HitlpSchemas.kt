package com.codefrak.engine.hitlp

import com.codefrak.engine.engineJson
import com.networknt.schema.Error
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.node.NullNode
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The HITLP spec's schemas, vendored under `src/main/resources/hitlp/` from `codefrak-oss/hitlp` `spec/` at the commit
 * `hitlp/HITLP_SPEC_COMMIT` names, with `hitlp/SHA256SUMS` over every vendored file (`HitlpVendoredFilesTest` fails when
 * one drifts, fetching nothing). JSON Schema 2020-12; every `$ref` resolves from the classpath: the schemas' `$id`
 * prefix maps to `classpath:hitlp/schemas/`, and the 2020-12 metaschema (`responseSchema`'s `$ref`) is the validator's
 * own. Format assertions are on, so a `deadline` that is no RFC 3339 date-time fails.
 */
object HitlpSchemas {
    const val ID_PREFIX = "https://github.com/codefrak-oss/hitlp/spec/schemas/"
    const val ENVELOPE = "envelope"
    const val ASK_REQUEST = "ask.request"
    const val APPROVE_REQUEST = "approve.request"
    const val ASK_RESPONSE = "ask.response"
    const val APPROVE_RESPONSE = "approve.response"
    const val DECISION_RECORD = "decision-record"
    val NAMES = listOf(ENVELOPE, ASK_REQUEST, ASK_RESPONSE, APPROVE_REQUEST, APPROVE_RESPONSE, DECISION_RECORD)

    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { b ->
        b.schemaIdResolvers { it.mapPrefix(ID_PREFIX, "classpath:hitlp/schemas/") }
        b.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
    }
    private val schemas = ConcurrentHashMap<String, Schema>()
    /** Keys sorted at every level: the canonical form a digest is taken over. */
    private val canonical = jacksonMapperBuilder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()

    /** The vendored schema [name] (one of [NAMES]), compiled once. */
    fun of(name: String): Schema = schemas.getOrPut(name) { registry.getSchema(SchemaLocation.of("$ID_PREFIX$name.schema.json")) }

    /** The vendored schema [name]'s JSON, as the file has it. */
    fun source(name: String): JsonNode = of(name).schemaNode

    /** A schema from a request's `responseSchema` (`spec/hitlp.md` §4.1), 2020-12, compiled afresh. */
    fun compile(responseSchema: Any?): Schema = registry.getSchema(tree(responseSchema))

    /** The request schema of [primitive] (`ask` or `approve`). */
    fun request(primitive: String): Schema = of(if (primitive == HitlpPrimitives.APPROVE) APPROVE_REQUEST else ASK_REQUEST)

    /** The response (decision record) schema of [primitive]. */
    fun response(primitive: String): Schema = of(if (primitive == HitlpPrimitives.APPROVE) APPROVE_RESPONSE else ASK_RESPONSE)

    /**
     * The engine's extensions of `requester`, kept out of the vendored
     * files so `SHA256SUMS` still pins the spec as published: `principal` a string, `autoApprovable` a boolean. The 0.2
     * envelope defines no `requester` at all (spec §5: 0.1's field is gone, its `agent` is `attested.requester`) and is
     * open at the top level, so the server's [requestProblem] types the two extensions here and validates the rest
     * against the spec. The decision record's `rule` needs no overlay: the record is open. The server's own answer paths
     * go in part 3 of #2443; this stays for them until then.
     */
    val REQUESTER_EXTENSIONS: Map<String, String> = linkedMapOf("principal" to "string", "autoApprovable" to "boolean")

    /**
     * The envelope members a requester never sends (spec §5, §5.1, R8): `requester` is 0.1's field and gone in 0.2, and
     * `attested` is the server's to fill from the credentials it authenticated. The 0.2 envelope is open at the top
     * level and defines both, so the schema alone passes them: [requesterProblem] refuses them by name first.
     */
    val NEVER_SENT: List<String> = listOf("requester", "attested")

    /**
     * The engine as a requester (`HitlpRequester`, `HitlpAsserted`): the first way [args] break [primitive]'s request as
     * the engine may send it, null when they fit. A request carrying one of [NEVER_SENT] is refused at that path before
     * the vendored `ask.request` / `approve.request` schema runs over the rest.
     */
    fun requesterProblem(primitive: String, args: Map<String, Any?>): String? {
        for (name in NEVER_SENT) if (name in args) return "/$name: a requester never sends it" +
            (if (name == "requester") " (0.1's field; 0.2 puts the requester under the server's attested)" else " (the server fills it, R8)")
        return firstProblem(request(primitive), args)
    }

    /** The first way [args] break [primitive]'s request schema with the engine's `requester` extensions; null when they fit. */
    fun requestProblem(primitive: String, args: Map<String, Any?>): String? {
        val requester = args["requester"] as? Map<*, *>
        if (requester != null) for ((name, type) in REQUESTER_EXTENSIONS) {
            if (name !in requester) continue
            val v = requester[name]
            if (if (type == "string") v !is String else v !is Boolean) return "/requester/$name: must be a $type"
        }
        val spec = if (requester == null) args else args + ("requester" to requester.filterKeys { it !in REQUESTER_EXTENSIONS })
        return firstProblem(request(primitive), spec)
    }

    /** Every way [value] breaks [schema], one line per instance path (`/path: why`), empty when it fits. */
    fun problems(schema: Schema, value: Any?): List<String> = problems(schema, tree(value))

    fun problems(schema: Schema, node: JsonNode): List<String> =
        schema.validate(node).groupBy { path(it) }.map { (path, errors) ->
            "$path: ${errors.joinToString(", or ") { it.message.removePrefix("$path: ").removePrefix("${it.instanceLocation}: ").trim() }}"
        }

    /** [value] as a tree; JSON `null` for null. */
    private fun tree(value: Any?): JsonNode = if (value == null) NullNode.instance else engineJson.valueToTree(value)

    /** The first failing path of [problems], or null when it fits. */
    fun firstProblem(schema: Schema, value: Any?): String? = problems(schema, value).firstOrNull()

    /** The instance path an error names: a missing required property is named at the property, not at its parent. */
    private fun path(e: Error): String {
        val at = e.instanceLocation.toString()
        val property = e.property?.takeIf { e.keyword == "required" || e.keyword == "dependentRequired" }
        return (if (property != null) "$at/$property" else at).ifEmpty { "/" }
    }

    /** `sha256:<hex>` over [value]'s canonical JSON (keys sorted at every level, no whitespace). */
    fun digest(value: Any?): String {
        val bytes = canonical.writeValueAsBytes(engineJson.convertValue(value, Any::class.java))
        return "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
