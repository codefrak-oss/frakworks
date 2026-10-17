package com.codefrak.engine.smith

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.annotation.JsonDeserialize
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.util.concurrent.ConcurrentHashMap

/**
 * The Smith result contract (engine/README.md, "Smith results"): one JSON Schema per mode,
 * `smith/schemas/<mode>.schema.json`, packed into the jar as `result-schemas/` and copied into the
 * Smith image, where `check-result <mode>` runs the same file. A result is checked against its
 * schema before it is parsed, so a wrong type is an incident naming the field, not a Jackson error.
 */
object ResultSchemas {
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    private val schemas = ConcurrentHashMap<String, Schema>()
    private val json = jacksonMapperBuilder().build()

    /** The schema for [mode], or null when there is none. */
    fun of(mode: String): Schema? = schemas[mode] ?: ResultSchemas::class.java.getResourceAsStream("/result-schemas/$mode.schema.json")
        ?.use { registry.getSchema(it) }?.also { schemas[mode] = it }

    /** Every way [bytes] break [mode]'s schema, each naming the field; empty when the types are right. */
    fun problems(mode: String, bytes: ByteArray): List<String> {
        val schema = of(mode) ?: return listOf("no result schema for mode '$mode'")
        val node = runCatching { json.readTree(bytes) }.getOrElse { return listOf("result.json is not JSON: ${it.message}") }
        // One line per field: an `anyOf` (a pass-along field) fails once per branch, at the same place.
        return schema.validate(node).groupBy { it.instanceLocation.toString().ifEmpty { "/" } }
            .map { (field, errors) -> "$field: ${errors.joinToString(", or ") { it.message.removePrefix("$field: ") }}" }
    }
}

/**
 * On a result field the engine only passes on (to a brief or a GitHub comment) and never branches on:
 * the field takes any JSON (the schema says non-empty) and reads as [PassAlongText.render] of it.
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@JacksonAnnotationsInside
@JsonDeserialize(using = PassAlongText::class)
annotation class PassAlong

/** [PassAlong] for a list whose items are each passed on. */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@JacksonAnnotationsInside
@JsonDeserialize(contentUsing = PassAlongText::class)
annotation class PassAlongItems

class PassAlongText : ValueDeserializer<String>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): String = render(ctxt.readTree(p))

    companion object {
        private val pretty = jacksonMapperBuilder().enable(SerializationFeature.INDENT_OUTPUT).build()

        /**
         * A string as itself; a number or boolean as its JSON; an object or array as a fenced JSON block,
         * indented, so a comment shows it readably. An empty object or array reads as blank, as `""` does.
         */
        fun render(node: JsonNode): String = when {
            node.isString -> node.asString()
            node.isNull -> ""
            node.isContainer -> if (node.isEmpty) "" else "```json\n${pretty.writeValueAsString(node)}\n```"
            else -> node.toString()
        }
    }
}
