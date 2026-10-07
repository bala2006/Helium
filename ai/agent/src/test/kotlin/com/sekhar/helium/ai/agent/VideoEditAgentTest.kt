package com.sekhar.helium.ai.agent

import com.sekhar.helium.ai.provider.AiModelProvider
import com.sekhar.helium.ai.provider.ModelRequest
import com.sekhar.helium.ai.provider.ModelResponse
import com.sekhar.helium.ai.tools.ToolCallRequest
import com.sekhar.helium.ai.tools.ToolNames
import com.sekhar.helium.ai.tools.ToolResultPayload
import com.sekhar.helium.core.common.SequentialIdGenerator
import com.sekhar.helium.core.model.AiModelConfig
import com.sekhar.helium.core.model.AiTokenUsage
import com.sekhar.helium.core.model.DeleteEdit
import com.sekhar.helium.core.model.RemoveRange
import com.sekhar.helium.core.network.GatewayException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Agent-loop behaviour under adversarial model output.
 *
 * These are the tests that protect users from a confused model: a malformed tool
 * call must become a correctable error rather than a corrupt edit, a repeated
 * call must be refused rather than looped forever, and a simulated network
 * failure must stop the loop with an explanation instead of a half-applied edit.
 */
class VideoEditAgentTest {

    private fun request(config: AiModelConfig = AiModelConfig(maxToolIterations = 8)) = AgentRequest(
        userPrompt = "remove the boring pauses",
        context = "### Project\nduration=0:10.0",
        config = config,
    )

    private fun agent(provider: AiModelProvider, dispatcher: AgentToolDispatcher) =
        VideoEditAgent(provider, dispatcher, SequentialIdGenerator("op"))

    private fun call(id: String, name: String, args: String = """{"videoId":"src-1"}""") =
        ToolCallRequest(callId = id, name = name, argumentsJson = args)

    private fun response(vararg toolCalls: ToolCallRequest, text: String = "") = ModelResponse(
        modelId = "gpt-6-luna",
        text = text,
        toolCalls = toolCalls.toList(),
        usage = AiTokenUsage(inputTokens = 100, outputTokens = 25),
    )

    // -----------------------------------------------------------------------------

    @Test
    fun `inspection then edit produces one grounded operation`() = runTest {
        val dispatcher = RecordingDispatcher()
        val provider = ScriptedProvider(
            response(call("c1", ToolNames.FIND_SILENCE, """{"videoId":"src-1","minDurationMs":500}"""), text = "checking"),
            response(
                call("c2", ToolNames.REMOVE_RANGE, """{"videoId":"src-1","startMs":1000,"endMs":2500}"""),
                text = "removing 1.0s-2.5s",
            ),
        )

        val result = agent(provider, dispatcher).run(request())

        assertEquals(1, result.operations.size)
        val edit = result.operations.first() as RemoveRange
        assertEquals("src-1", edit.sourceId.value)
        assertEquals(1_000L, edit.range.startMs)
        assertEquals(2_500L, edit.range.endMsExclusive)
        assertEquals(AgentStopReason.COMPLETED, result.stopReason)
        assertTrue(result.producedEdits)
        assertEquals(2, result.iterations)
        // The inspection call really reached the on-device dispatcher.
        assertEquals(1, dispatcher.calls.size)
        assertFalse(provider.sawEmptyCatalog, "the agent must advertise tools to the provider")
    }

    @Test
    fun `malformed editing arguments never produce an operation`() = runTest {
        val provider = ScriptedProvider(
            // endMs before startMs and no videoId: both invalid.
            response(call("c1", ToolNames.REMOVE_RANGE, """{"startMs":9000,"endMs":100}""")),
            response(text = "I could not do that."),
        )

        val result = agent(provider, RecordingDispatcher()).run(request())

        assertTrue(result.operations.isEmpty())
        assertFalse(result.producedEdits)
        assertTrue(result.errors.isNotEmpty())
        assertEquals(AgentStopReason.INVALID_ONLY, result.stopReason)
    }

    @Test
    fun `a repeated identical call is refused and the loop still terminates`() = runTest {
        val dispatcher = RecordingDispatcher()
        val provider = ScriptedProvider(
            response(call("c1", ToolNames.GET_VIDEO_OVERVIEW)),
            response(call("c2", ToolNames.GET_VIDEO_OVERVIEW)),
            response(call("c3", ToolNames.GET_VIDEO_OVERVIEW)),
            response(text = "done"),
        )

        val result = agent(provider, dispatcher).run(request())

        // The same call is executed at most twice; the third is refused.
        assertEquals(2, dispatcher.calls.size)
        assertTrue(result.errors.any { it.contains("repeated") })
        assertTrue(result.operations.isEmpty())
    }

    @Test
    fun `iteration bound stops a model that only ever inspects`() = runTest {
        val dispatcher = RecordingDispatcher()
        val provider = ScriptedProvider(
            *Array(10) { index ->
                response(
                    call(
                        "c$index",
                        ToolNames.GET_AUDIO_EVENTS,
                        """{"videoId":"src-1","startMs":${index * 100},"endMs":${index * 100 + 99}}""",
                    ),
                )
            },
        )

        val result = agent(provider, dispatcher).run(request(AiModelConfig(maxToolIterations = 3)))

        assertEquals(3, result.iterations)
        assertEquals(3, dispatcher.calls.size)
        assertEquals(AgentStopReason.MAX_ITERATIONS, result.stopReason)
        assertTrue(result.operations.isEmpty())
    }

    @Test
    fun `offline provider stops the loop with an explanation`() = runTest {
        val result = agent(OfflineProvider(), RecordingDispatcher()).run(request())

        assertEquals(AgentStopReason.OFFLINE, result.stopReason)
        assertTrue(result.operations.isEmpty())
        assertTrue(result.summary.contains("offline", ignoreCase = true))
    }

    @Test
    fun `unknown tool names never reach the dispatcher`() = runTest {
        val dispatcher = RecordingDispatcher()
        val provider = ScriptedProvider(
            response(call("c1", "run_shell_command", """{"cmd":"rm -rf /"}""")),
            response(text = "Understood."),
        )

        val result = agent(provider, dispatcher).run(request())

        assertTrue(dispatcher.calls.isEmpty())
        assertTrue(result.errors.any { it.contains("Unknown tool") })
        assertTrue(result.operations.isEmpty())
    }

    @Test
    fun `reverting an earlier edit arrives as a DeleteEdit operation`() = runTest {
        val provider = ScriptedProvider(
            response(call("c1", ToolNames.DELETE_EDIT, """{"targetTransactionId":"tx-37"}"""), text = "reverting the zoom"),
        )

        val result = agent(provider, RecordingDispatcher()).run(request())

        assertEquals(1, result.operations.size)
        assertTrue(result.operations.first() is DeleteEdit)
        assertEquals("tx-37", (result.operations.first() as DeleteEdit).targetTransactionId)
        assertEquals(AgentStopReason.COMPLETED, result.stopReason)
    }

    @Test
    fun `tool output is truncated so one verbose tool cannot blow the budget`() = runTest {
        val dispatcher = RecordingDispatcher(hugePayload = true)
        val provider = ScriptedProvider(
            response(call("c1", ToolNames.GET_TRANSCRIPT)),
            response(call("c2", ToolNames.REMOVE_RANGE, """{"videoId":"src-1","startMs":0,"endMs":500}""")),
        )

        agent(provider, dispatcher).run(request())

        val transcript = requireNotNull(
            provider.seenMessages.flatMap { it }.firstOrNull { it.toolName == ToolNames.GET_TRANSCRIPT },
        ) { "the tool result must be fed back to the model" }
        assertTrue(
            transcript.toolResultJson.orEmpty().length <= DEFAULT_BUDGET,
            "tool results must be truncated before they are sent back",
        )
    }

    // -----------------------------------------------------------------------------
    // Fakes
    // -----------------------------------------------------------------------------

    private class ScriptedProvider(vararg val responses: ModelResponse) : AiModelProvider {
        override val id: String = "test"
        override val displayName: String = "Test provider"
        var sawEmptyCatalog: Boolean = false
        val seenMessages = mutableListOf<List<com.sekhar.helium.ai.provider.ModelMessage>>()
        private var index = 0

        override suspend fun generate(request: ModelRequest): ModelResponse {
            if (request.tools.isEmpty()) sawEmptyCatalog = true
            seenMessages += request.messages
            val response = responses.getOrNull(index) ?: ModelResponse(
                modelId = "test",
                text = "",
                toolCalls = emptyList(),
                usage = AiTokenUsage(),
            )
            index++
            return response
        }
    }

    private class OfflineProvider : AiModelProvider {
        override val id: String = "offline"
        override val displayName: String = "Offline provider"
        override suspend fun generate(request: ModelRequest): ModelResponse = throw GatewayException.Offline()
    }

    private class RecordingDispatcher(private val hugePayload: Boolean = false) : AgentToolDispatcher {
        val calls = mutableListOf<ToolCallRequest>()

        override suspend fun execute(call: ToolCallRequest): ToolResultPayload {
            calls += call
            val content = when {
                hugePayload -> "x".repeat(50_000)
                call.name == ToolNames.FIND_SILENCE -> """{"silences":[{"startMs":1000,"endMs":2500}]}"""
                call.name == ToolNames.GET_VIDEO_OVERVIEW -> """{"videoId":"src-1","durationMs":10000,"scenes":[]}"""
                else -> """{"ok":true}"""
            }
            return ToolResultPayload(callId = call.callId, name = call.name, ok = true, content = content)
        }

        override fun describe(call: ToolCallRequest): String = "Inspecting ${call.name}"
    }

    private companion object {
        /** Mirrors `VideoEditAgent.MAX_TOOL_RESULT_CHARS`, which is private by design. */
        const val DEFAULT_BUDGET = 6_000
    }
}
