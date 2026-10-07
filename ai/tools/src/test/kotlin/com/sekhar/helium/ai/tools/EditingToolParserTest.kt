package com.sekhar.helium.ai.tools

import com.sekhar.helium.core.model.AddCaption
import com.sekhar.helium.core.model.AddZoom
import com.sekhar.helium.core.model.AspectRatio
import com.sekhar.helium.core.model.EditOperation
import com.sekhar.helium.core.model.RemoveRange
import com.sekhar.helium.core.model.SetSpeed
import com.sekhar.helium.core.model.TimeRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class EditingToolParserTest {

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun parse(name: String, json: String) = EditingToolParser.parse(name, args(json), "op-1")

    private fun parsed(outcome: ParseOutcome): EditOperation = when (outcome) {
        is ParseOutcome.Parsed -> outcome.operation
        is ParseOutcome.Invalid -> fail("expected a parsed operation but got ${outcome.errors}")
    }

    private fun errors(outcome: ParseOutcome): List<String> = when (outcome) {
        is ParseOutcome.Invalid -> outcome.errors
        is ParseOutcome.Parsed -> fail("expected a validation error but got ${outcome.operation}")
    }

    @Test
    fun parsesAWellFormedRemoveRange() {
        val operation = parsed(
            parse(ToolNames.REMOVE_RANGE, """{"videoId":"src-1","startMs":1820,"endMs":3140}"""),
        ) as RemoveRange
        assertEquals(TimeRange(1_820L, 3_140L), operation.range)
        assertEquals("src-1", operation.sourceId.value)
    }

    @Test
    fun acceptsNumbersProvidedAsStrings() {
        val operation = parsed(
            parse(ToolNames.SET_SPEED, """{"videoId":"src-1","startMs":"6100","endMs":"9840","speed":"1.25"}"""),
        ) as SetSpeed
        assertEquals(1.25f, operation.speed)
    }

    @Test
    fun missingRequiredFieldIsReportedByName() {
        val messages = errors(parse(ToolNames.REMOVE_RANGE, """{"videoId":"src-1","startMs":0}"""))
        assertTrue(messages.any { it.contains("endMs") }, "got $messages")
    }

    @Test
    fun invertedRangeIsRejectedBeforeConstruction() {
        val messages = errors(
            parse(ToolNames.REMOVE_RANGE, """{"videoId":"src-1","startMs":5000,"endMs":1000}"""),
        )
        assertTrue(messages.any { it.contains("greater than") }, "got $messages")
    }

    @Test
    fun outOfRangeSpeedIsRejected() {
        val messages = errors(
            parse(ToolNames.SET_SPEED, """{"videoId":"src-1","startMs":0,"endMs":1000,"speed":400}"""),
        )
        assertTrue(messages.any { it.contains("speed") }, "got $messages")
    }

    @Test
    fun outOfBoundsCoordinatesAreRejected() {
        val messages = errors(
            parse(
                ToolNames.BLUR_REGION,
                """{"videoId":"src-1","startMs":0,"endMs":1000,"left":-0.2,"top":0.1,"right":0.5,"bottom":0.6}""",
            ),
        )
        assertTrue(messages.any { it.contains("left") }, "got $messages")
    }

    @Test
    fun unknownToolNameIsRejected() {
        val messages = errors(parse("rm_rf", """{}"""))
        assertTrue(messages.any { it.contains("unknown editing tool") }, "got $messages")
    }

    @Test
    fun aspectRatioParsingHandlesAliasesAndDecimals() {
        assertEquals(AspectRatio.PORTRAIT_9_16, EditingToolParser.parseAspectRatio("9:16"))
        assertEquals(AspectRatio.PORTRAIT_9_16, EditingToolParser.parseAspectRatio("reel"))
        assertEquals(AspectRatio.PORTRAIT_9_16, EditingToolParser.parseAspectRatio("0.5625"))
        assertEquals(AspectRatio.LANDSCAPE_16_9, EditingToolParser.parseAspectRatio("16x9"))
        assertEquals(AspectRatio.SQUARE_1_1, EditingToolParser.parseAspectRatio("square"))
        assertEquals(AspectRatio.ORIGINAL, EditingToolParser.parseAspectRatio("original"))
        assertEquals(null, EditingToolParser.parseAspectRatio("banana"))
    }

    @Test
    fun parsesZoomWithDefaults() {
        val zoom = parsed(
            parse(ToolNames.ADD_ZOOM, """{"videoId":"src-1","startMs":48400,"endMs":49200,"scale":2.5}"""),
        ) as AddZoom
        assertEquals(2.5f, zoom.scale)
        assertEquals(0.5f, zoom.focusX)
        assertEquals(250L, zoom.easeInMs)
    }

    @Test
    fun parsesCaptionCuesAndWords() {
        val json = """
            {
              "videoId": "src-1",
              "cues": [
                {
                  "startMs": 1000, "endMs": 2000, "text": "here we go",
                  "words": [
                    {"startMs": 1000, "endMs": 1400, "text": "here"},
                    {"startMs": 1400, "endMs": 2000, "text": "we go"}
                  ]
                }
              ]
            }
        """.trimIndent()
        val caption = parsed(parse(ToolNames.ADD_CAPTION, json)) as AddCaption
        assertEquals(1, caption.cues.size)
        assertEquals(2, caption.cues.first().words.size)
    }

    @Test
    fun rejectsEmptyCaptionCues() {
        errors(parse(ToolNames.ADD_CAPTION, """{"videoId":"src-1","cues":[]}"""))
    }

    @Test
    fun dropsInvalidWordTimingsButKeepsTheCue() {
        val json = """
            {
              "videoId": "src-1",
              "cues": [
                {"startMs": 1000, "endMs": 2000, "text": "hello",
                 "words": [{"startMs": 1900, "endMs": 1200, "text": "bad"}]}
              ]
            }
        """.trimIndent()
        val caption = parsed(parse(ToolNames.ADD_CAPTION, json)) as AddCaption
        assertTrue(caption.cues.first().words.isEmpty(), "invalid word timings must be dropped")
    }

    @Test
    fun deleteEditNeedsATarget() {
        errors(parse(ToolNames.DELETE_EDIT, """{}"""))
        parsed(parse(ToolNames.DELETE_EDIT, """{"targetTransactionId":"tx-1"}"""))
    }
}
