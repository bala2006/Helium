package com.sekhar.helium.ai.agent

import com.sekhar.helium.ai.provider.AiModelProvider
import com.sekhar.helium.ai.provider.ModelMessage
import com.sekhar.helium.ai.provider.ModelRequest
import com.sekhar.helium.ai.provider.ModelRole
import com.sekhar.helium.ai.tools.EditingToolParser
import com.sekhar.helium.ai.tools.ParseOutcome
import com.sekhar.helium.ai.tools.ToolCallRequest
import com.sekhar.helium.ai.tools.ToolCatalog
import com.sekhar.helium.ai.tools.ToolResultPayload
import com.sekhar.helium.core.common.HeliumJson
import com.sekhar.helium.core.common.HeliumLog
import com.sekhar.helium.core.common.IdGenerator
import com.sekhar.helium.core.common.NoOpLog
import com.sekhar.helium.core.model.AgentProgress
import com.sekhar.helium.core.model.AgentStage
import com.sekhar.helium.core.model.AiModelConfig
import com.sekhar.helium.core.model.AiUsage
import com.sekhar.helium.core.model.EditOperation
import com.sekhar.helium.core.network.GatewayException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Executes one inspection tool and returns its evidence.
 *
 * Implemented by the app, because retrieval needs the semantic index, the
 * project store and the frame cache — none of which belong in the agent.
 */
interface AgentToolDispatcher {
    suspend fun execute(call: ToolCallRequest): ToolResultPayload

    /** Short, user-visible description of what this call is doing. */
    fun describe(call: ToolCallRequest): String
}

/** One natural-language instruction from the user. */
data class AgentRequest(
    val userPrompt: String,
    /** Compact project state plus the video overview; the level-1 context. */
    val context: String,
    val config: AiModelConfig,
    val idempotencyKey: String? = null,
)

/** Why the loop stopped. Drives the message shown under the AI field. */
enum class AgentStopReason {
    COMPLETED,
    NO_EDITS,
    MAX_ITERATIONS,
    REPEATED_CALLS,
    PROVIDER_ERROR,
    OFFLINE,
    INVALID_ONLY,
}

/** Everything the UI needs after one instruction. */
data class AgentResult(
    val operations: List<EditOperation>,
    val assistantText: String,
    val summary: String,
    val iterations: Int,
    val usage: AiUsage,
    val stopReason: AgentStopReason,
    val errors: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val progress: List<AgentProgress> = emptyList(),
) {
    val producedEdits: Boolean get() = operations.isNotEmpty()
}

@Serializable
private data class ToolErrorPayload(val error: String)

/**
 * The bounded tool-use loop.
 *
 * Three properties matter more than anything else here:
 *
 * * **It is bounded.** The loop can never run more than
 *   [AiModelConfig.maxToolIterations] times, and identical calls are detected and
 *   refused so a confused model cannot spin.
 * * **It is grounded.** Every claim the model makes about the video has to come
 *   from a tool result; editing calls with invalid arguments become tool errors
 *   the model can correct, and a repeated failure ends the loop with an
 *   explanation instead of a half-applied edit.
 * * **It never touches the timeline.** It returns [AgentResult.operations] and
 *   lets the caller apply them as one atomic transaction.
 */
class VideoEditAgent(
    private val provider: AiModelProvider,
    private val dispatcher: AgentToolDispatcher,
    private val ids: IdGenerator,
    private val log: HeliumLog = NoOpLog,
) {

    suspend fun run(
        request: AgentRequest,
        onProgress: (AgentProgress) -> Unit = {},
        toolResultBudgetChars: Int = MAX_TOOL_RESULT_CHARS,
    ): AgentResult {
        val config = request.config
        val instructions = AgentPrompts.system(config, request.context)
        val schemas = ToolCatalog.schemas()

        val conversation = mutableListOf(
            ModelMessage(role = ModelRole.USER, text = request.userPrompt),
        )
        val operations = mutableListOf<EditOperation>()
        val errors = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val progressLog = mutableListOf<AgentProgress>()
        val repeats = mutableMapOf<String, Int>()

        var usage = AiUsage()
        var iterations = 0
        var stopReason = AgentStopReason.NO_EDITS
        var assistantText = ""

        fun emit(stage: AgentStage, message: String) {
            val progress = AgentProgress(stage = stage, message = message, iteration = iterations)
            progressLog += progress
            onProgress(progress)
        }

        while (iterations < config.maxToolIterations) {
            iterations++
            emit(
                if (iterations == 1) AgentStage.UNDERSTANDING else AgentStage.REASONING,
                if (iterations == 1) "Understanding your request" else "Thinking about the next step",
            )

            val response = try {
                provider.generate(
                    ModelRequest(
                        instructions = instructions,
                        messages = conversation.toList(),
                        tools = schemas,
                        config = config,
                        idempotencyKey = request.idempotencyKey?.let { "$it-$iterations" },
                    ),
                )
            } catch (offline: GatewayException.Offline) {
                emit(AgentStage.OFFLINE, "You are offline")
                return finish(
                    operations, assistantText, iterations, usage, AgentStopReason.OFFLINE,
                    errors + "The AI needs a connection. Your project and manual edits are unaffected.",
                    notes, progressLog,
                )
            } catch (timeout: GatewayException.Timeout) {
                emit(AgentStage.FAILED, "The AI request timed out")
                return finish(
                    operations, assistantText, iterations, usage, AgentStopReason.PROVIDER_ERROR,
                    errors + "The AI request timed out. Try again or simplify the instruction.",
                    notes, progressLog,
                )
            } catch (failure: GatewayException) {
                emit(AgentStage.FAILED, failure.message ?: "The AI request failed")
                return finish(
                    operations, assistantText, iterations, usage, AgentStopReason.PROVIDER_ERROR,
                    errors + (failure.message ?: "The AI request failed."),
                    notes, progressLog,
                )
            }

            usage = usage + AiUsage(tokens = response.usage, requests = 1)
            notes += response.notes
            if (response.text.isNotBlank()) assistantText = response.text

            if (response.toolCalls.isEmpty()) {
                stopReason = if (operations.isEmpty()) AgentStopReason.NO_EDITS else AgentStopReason.COMPLETED
                emit(AgentStage.SUMMARIZING, "Preparing the summary")
                break
            }

            // Record the assistant turn before its results, as providers expect.
            // The calls ride along with the turn so the gateway can emit the
            // matching `function_call` items ahead of their outputs.
            conversation += ModelMessage(
                role = ModelRole.ASSISTANT,
                text = response.text,
                toolCalls = response.toolCalls,
            )

            val toolMessages = mutableListOf<ModelMessage>()
            var producedEditsThisTurn = false
            var unknownOrInvalid = 0

            for (call in response.toolCalls) {
                val signature = "${call.name}|${call.argumentsJson.filterNot { it.isWhitespace() }}"
                val seen = (repeats[signature] ?: 0) + 1
                repeats[signature] = seen
                if (seen > MAX_IDENTICAL_CALLS) {
                    errors += "The AI repeated the same ${call.name} call; stopping."
                    toolMessages += errorMessage(call, "You already called $call.name with these arguments. Use a different tool or finish.")
                    continue
                }

                when {
                    ToolCatalog.mutatingNames.contains(call.name) -> {
                        when (val parsed = EditingToolParser.parse(call.name, parseArgs(call), ids.newId())) {
                            is ParseOutcome.Parsed -> {
                                operations += parsed.operation
                                producedEditsThisTurn = true
                            }
                            is ParseOutcome.Invalid -> {
                                unknownOrInvalid++
                                val message = parsed.errors.joinToString("; ")
                                errors += "${call.name}: $message"
                                toolMessages += errorMessage(call, message)
                            }
                        }
                    }

                    ToolCatalog.isKnown(call.name) -> {
                        emit(AgentStage.INSPECTING, dispatcher.describe(call))
                        val payload = dispatcher.execute(call)
                        usage = usage + AiUsage(
                            toolCalls = 1,
                            toolFailures = if (payload.ok) 0 else 1,
                            imagesSent = payload.images.size,
                        )
                        val content = payload.content.take(toolResultBudgetChars)
                        toolMessages += ModelMessage(
                            role = ModelRole.TOOL,
                            toolCallId = call.callId,
                            toolName = call.name,
                            toolResultJson = if (payload.ok) content else errorJson(payload.error ?: "Tool failed"),
                        )
                    }

                    else -> {
                        unknownOrInvalid++
                        errors += "Unknown tool ${call.name}"
                        toolMessages += errorMessage(call, "There is no tool called ${call.name}.")
                    }
                }
            }

            conversation += toolMessages

            if (producedEditsThisTurn) {
                stopReason = AgentStopReason.COMPLETED
                emit(AgentStage.PLANNING_EDITS, "Planning ${operations.size} edit(s)")
                break
            }

            if (unknownOrInvalid > 0 && operations.isEmpty()) {
                // Unusable calls only, so far: loop again so the model can correct
                // itself. A persistent failure stops on the repeat guard or the
                // iteration bound, never by looping forever.
                log.d(TAG, "Invalid tool calls so far: $unknownOrInvalid")
            }
        }

        if (iterations >= config.maxToolIterations && operations.isEmpty()) {
            stopReason = if (stopReason == AgentStopReason.NO_EDITS) AgentStopReason.MAX_ITERATIONS else stopReason
        }
        if (operations.isEmpty() && errors.isNotEmpty() && stopReason == AgentStopReason.NO_EDITS) {
            stopReason = AgentStopReason.INVALID_ONLY
        }

        return finish(operations, assistantText, iterations, usage, stopReason, errors, notes, progressLog)
    }

    private fun finish(
        operations: List<EditOperation>,
        assistantText: String,
        iterations: Int,
        usage: AiUsage,
        stopReason: AgentStopReason,
        errors: List<String>,
        notes: List<String>,
        progress: List<AgentProgress>,
    ): AgentResult = AgentResult(
        operations = operations,
        assistantText = assistantText,
        summary = summarize(operations, stopReason, errors),
        iterations = iterations,
        usage = usage,
        stopReason = stopReason,
        errors = errors,
        notes = notes,
        progress = progress,
    )

    private fun summarize(
        operations: List<EditOperation>,
        stopReason: AgentStopReason,
        errors: List<String>,
    ): String = when {
        operations.isNotEmpty() -> "Planned ${operations.size} edit${if (operations.size == 1) "" else "s"}."
        stopReason == AgentStopReason.OFFLINE -> "You are offline. The AI needs a connection."
        stopReason == AgentStopReason.PROVIDER_ERROR -> errors.firstOrNull() ?: "The AI request failed."
        stopReason == AgentStopReason.MAX_ITERATIONS -> "The AI reached its inspection limit without proposing edits."
        stopReason == AgentStopReason.REPEATED_CALLS -> "The AI got stuck repeating itself, so it stopped."
        stopReason == AgentStopReason.INVALID_ONLY -> errors.firstOrNull() ?: "The AI proposed edits that were not valid."
        else -> "The AI did not propose any edits."
    }

    private fun parseArgs(call: ToolCallRequest): kotlinx.serialization.json.JsonObject =
        runCatching {
            HeliumJson.parseToJsonElement(call.argumentsJson) as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: kotlinx.serialization.json.JsonObject(emptyMap())

    private fun errorMessage(call: ToolCallRequest, message: String) = ModelMessage(
        role = ModelRole.TOOL,
        toolCallId = call.callId,
        toolName = call.name,
        toolResultJson = errorJson(message),
    )

    private fun errorJson(message: String): String =
        HeliumJson.encodeToString(ToolErrorPayload(message))

    private companion object {
        const val TAG = "VideoEditAgent"

        /** Tool output is truncated so one verbose tool cannot blow the budget. */
        const val MAX_TOOL_RESULT_CHARS = 6_000

        /** How many times the exact same call is tolerated before the loop stops. */
        const val MAX_IDENTICAL_CALLS = 2
    }
}
