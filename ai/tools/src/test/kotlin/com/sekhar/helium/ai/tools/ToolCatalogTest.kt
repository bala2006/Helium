package com.sekhar.helium.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ToolCatalogTest {

    /**
     * Tools that describe existing state and therefore take no arguments.
     *
     * They still declare an empty `properties` object and
     * `additionalProperties: false`, so a model that invents arguments for them
     * gets a validation error instead of being silently trusted.
     */
    private val ZERO_ARGUMENT_TOOLS = setOf(ToolNames.GET_PROJECT_STATE)

    private fun invalidMessages(outcome: ParseOutcome): List<String> = when (outcome) {
        is ParseOutcome.Invalid -> outcome.errors
        is ParseOutcome.Parsed -> fail("expected a validation error but got ${outcome.operation}")
    }

    @Test
    fun everyEditingToolHasAParserBranch() {
        ToolCatalog.definitions.filter { it.mutatesTimeline }.forEach { definition ->
            // An empty argument object must produce a field-level validation error,
            // never "unknown editing tool": that would mean the parser lost a branch.
            val messages = invalidMessages(
                EditingToolParser.parse(definition.name, JsonObject(emptyMap()), "op-1"),
            )
            assertTrue(
                messages.none { it.startsWith("unknown editing tool") },
                "${definition.name} is missing from EditingToolParser: $messages",
            )
        }
    }

    @Test
    fun everyNameDeclaredInToolNamesIsInTheCatalog() {
        val declared = ToolNames::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.get(null) as String }
        assertTrue(declared.isNotEmpty())
        declared.forEach { name ->
            assertTrue(name in ToolCatalog.names, "ToolNames declares '$name' but the catalog does not")
        }
    }

    @Test
    fun schemasAreStrictJsonObjects() {
        ToolCatalog.definitions.forEach { definition ->
            val schema = definition.parameters
            assertEquals("object", schema["type"]?.jsonPrimitive?.content, "${definition.name}: type")
            assertEquals("false", schema["additionalProperties"]?.jsonPrimitive?.content, "${definition.name}: additionalProperties")

            val properties = schema["properties"]?.jsonObject
                ?: fail("${definition.name}: missing properties")
            assertTrue(
                properties.isNotEmpty() || definition.name in ZERO_ARGUMENT_TOOLS,
                "${definition.name}: properties must not be empty",
            )
            if (definition.name in ZERO_ARGUMENT_TOOLS) {
                assertTrue(
                    schema["required"] == null,
                    "${definition.name}: a tool that takes no input must not require any",
                )
            }

            val requiredElement = schema["required"]
            if (requiredElement != null) {
                val required = (requiredElement as JsonArray).map { it.jsonPrimitive.content }
                required.forEach { key ->
                    assertTrue(key in properties, "${definition.name}: required '$key' is not a declared property")
                }
            }
        }
    }

    @Test
    fun mutatingAndImageProducingSetsAreSane() {
        assertTrue(ToolNames.REMOVE_RANGE in ToolCatalog.mutatingNames)
        assertTrue(ToolNames.GET_PROJECT_STATE !in ToolCatalog.mutatingNames)
        assertTrue(ToolNames.INSPECT_SEGMENT in ToolCatalog.imageProducingNames)
        assertTrue(ToolNames.FIND_SILENCE !in ToolCatalog.imageProducingNames, "silence evidence is text only")
        assertTrue(ToolNames.GET_PROJECT_STATE !in ToolCatalog.imageProducingNames)
    }

    @Test
    fun schemasExposeEveryToolToTheProvider() {
        assertEquals(ToolCatalog.definitions.size, ToolCatalog.schemas().size)
        val names = ToolCatalog.schemas().map { it.name }.toSet()
        assertEquals(ToolCatalog.names, names)
    }
}
