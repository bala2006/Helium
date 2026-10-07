package com.sekhar.helium.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One described property in a tool's JSON Schema. */
 data class SchemaProp(
    val name: String,
    val schema: JsonObject,
    val required: Boolean = true,
)

internal fun jsonObject(vararg props: SchemaProp): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        props.forEach { prop -> put(prop.name, prop.schema) }
    }
    put("additionalProperties", false)
    val required = props.filter { it.required }.map { it.name }
    if (required.isNotEmpty()) {
        put("required", buildJsonArray { required.forEach { add(it) } })
    }
}

internal fun stringSchema(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    if (enum != null) {
        put("enum", buildJsonArray { enum.forEach { add(it) } })
    }
}

internal fun numberSchema(description: String, minimum: Double? = null, maximum: Double? = null): JsonObject =
    buildJsonObject {
        put("type", "number")
        put("description", description)
        if (minimum != null) put("minimum", minimum)
        if (maximum != null) put("maximum", maximum)
    }

internal fun integerSchema(description: String, minimum: Double? = null, maximum: Double? = null): JsonObject =
    buildJsonObject {
        put("type", "integer")
        put("description", description)
        if (minimum != null) put("minimum", minimum)
        if (maximum != null) put("maximum", maximum)
    }

internal fun booleanSchema(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

internal fun arraySchema(description: String, items: JsonObject): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", items)
}

internal fun objectSchema(description: String, vararg props: SchemaProp): JsonObject {
    val base = jsonObject(*props)
    return buildJsonObject {
        put("description", description)
        base.forEach { (key, value) -> put(key, value) }
    }
}
