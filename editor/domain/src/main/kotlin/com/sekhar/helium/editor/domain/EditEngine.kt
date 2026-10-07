package com.sekhar.helium.editor.domain

import com.sekhar.helium.core.common.IdGenerator
import com.sekhar.helium.core.common.TimeProvider
import com.sekhar.helium.core.model.AspectRatio
import com.sekhar.helium.core.model.DeleteEdit
import com.sekhar.helium.core.model.EditHistory
import com.sekhar.helium.core.model.EditOperation
import com.sekhar.helium.core.model.EditOutcome
import com.sekhar.helium.core.model.EditTransaction
import com.sekhar.helium.core.model.Project
import com.sekhar.helium.core.model.SetAspectRatioOp
import com.sekhar.helium.core.model.SourceId
import com.sekhar.helium.core.model.Timeline
import com.sekhar.helium.core.model.TransactionSource
import com.sekhar.helium.core.model.VideoMetadata
import com.sekhar.helium.core.model.buildBaseTimeline

/** The project after an edit, plus a user-facing outcome. */
data class ProjectEdit(
    val project: Project,
    val outcome: EditOutcome,
)

/**
 * Applies, undoes and redoes edits against the immutable project.
 *
 * The engine owns two guarantees that the rest of the app relies on:
 *
 * 1. **Atomicity.** A transaction is validated in full before it is committed.
 *    If any operation fails, the returned project is the one that was passed in,
 *    so a malformed model response can never leave a half-edited timeline.
 * 2. **Replayability.** The timeline is always recomputed from
 *    [Project.baseTimeline] plus the applied transactions. Undo is therefore
 *    exact and needs no inverse operations.
 */
class EditEngine(
    private val ids: IdGenerator,
    private val time: TimeProvider,
    private val reducer: TimelineReducer = TimelineReducer(),
) {

    /** Applies [operations] as one atomic transaction. */
    fun applyTransaction(
        project: Project,
        userPrompt: String?,
        operations: List<EditOperation>,
        source: TransactionSource = TransactionSource.AI,
    ): ProjectEdit {
        if (operations.isEmpty()) {
            return ProjectEdit(project, EditOutcome.failure(listOf("The AI did not propose any edits")))
        }
        val sources = project.sources.associate { it.id to it.metadata }
        var historyChanged = false

        // "Undo the zoom you just added" arrives as delete_edit, which changes
        // history rather than the timeline, so it is resolved before replaying.
        var history = project.history
        val structural = mutableListOf<EditOperation>()
        operations.forEach { operation ->
            if (operation is DeleteEdit) {
                history = history.remove(operation.targetTransactionId)
                historyChanged = history.transactions.size != project.history.transactions.size
            } else {
                structural += operation
            }
        }

        val replayed = replay(project, history)
        var timeline = replayed.timeline
        var aspectRatio = replayed.aspectRatio
        val warnings = replayed.warnings.toMutableList()
        var applied = 0

        for (operation in structural) {
            when (operation) {
                is SetAspectRatioOp -> {
                    aspectRatio = operation.aspectRatio
                    applied++
                }
                is DeleteEdit -> Unit
                else -> when (val result = reducer.apply(timeline, operation, sources)) {
                    is ReduceResult.Success -> {
                        timeline = result.timeline
                        warnings += result.warnings
                        applied++
                    }
                    is ReduceResult.Failure ->
                        // Nothing is committed: the caller keeps the original project.
                        return ProjectEdit(project, EditOutcome.failure(result.errors))
                }
            }
        }

        if (applied == 0 && !historyChanged) {
            return ProjectEdit(project, EditOutcome.failure(listOf("No edits were applied")))
        }

        val normalized = timeline.normalized()
        val transaction = EditTransaction(
            id = ids.newId(),
            index = history.nextIndex,
            userPrompt = userPrompt,
            summary = OperationSummary.summarize(operations, project, normalized),
            operations = structural,
            createdAtEpochMs = time.nowEpochMs(),
            source = source,
        )
        val updated = project.copy(
            timeline = normalized,
            aspectRatio = aspectRatio,
            history = history.append(transaction),
            updatedAtEpochMs = time.nowEpochMs(),
            revision = project.revision + 1,
        )

        return ProjectEdit(
            project = updated,
            outcome = EditOutcome(
                success = true,
                summary = transaction.summary,
                transaction = transaction,
                warnings = warnings,
                appliedOperationCount = applied,
            ),
        )
    }

    /** Reverts the last [count] transactions. */
    fun undo(project: Project, count: Int = 1): ProjectEdit {
        val history = project.history.undo(count)
        if (history.cursor == project.history.cursor) {
            return ProjectEdit(project, EditOutcome.failure(listOf("Nothing to undo")))
        }
        val replayed = replay(project, history)
        return ProjectEdit(
            project = project.copy(
                timeline = replayed.timeline.normalized(),
                aspectRatio = replayed.aspectRatio,
                history = history,
                updatedAtEpochMs = time.nowEpochMs(),
                revision = project.revision + 1,
            ),
            outcome = EditOutcome(
                success = true,
                summary = "Undid ${project.history.cursor - history.cursor} edit(s)",
            ),
        )
    }

    /** Re-applies undone transactions. */
    fun redo(project: Project, count: Int = 1): ProjectEdit {
        val history = project.history.redo(count)
        if (history.cursor == project.history.cursor) {
            return ProjectEdit(project, EditOutcome.failure(listOf("Nothing to redo")))
        }
        val replayed = replay(project, history)
        return ProjectEdit(
            project = project.copy(
                timeline = replayed.timeline.normalized(),
                aspectRatio = replayed.aspectRatio,
                history = history,
                updatedAtEpochMs = time.nowEpochMs(),
                revision = project.revision + 1,
            ),
            outcome = EditOutcome(
                success = true,
                summary = "Redid ${history.cursor - project.history.cursor} edit(s)",
            ),
        )
    }

    /**
     * Recomputes the timeline for [history].
     *
     * Public because the export and preview paths need the same deterministic
     * answer the editor has, including after a process restart.
     */
    fun replay(project: Project, history: EditHistory = project.history): ReplayResult {
        val sources: Map<SourceId, VideoMetadata> = project.sources.associate { it.id to it.metadata }
        val base = project.baseTimeline.takeUnless { it.isEmpty && project.sources.isNotEmpty() }
            ?: buildBaseTimeline(project.sources, ids)

        var timeline = base
        var aspectRatio: AspectRatio = project.aspectRatio
        val warnings = mutableListOf<String>()

        history.appliedTransactions.forEach { transaction ->
            transaction.operations.forEach { operation ->
                when (operation) {
                    is SetAspectRatioOp -> aspectRatio = operation.aspectRatio
                    is DeleteEdit -> Unit
                    else -> when (val result = reducer.apply(timeline, operation, sources)) {
                        is ReduceResult.Success -> {
                            timeline = result.timeline
                            warnings += result.warnings
                        }
                        is ReduceResult.Failure -> warnings +=
                            "Replay ignored an operation that previously succeeded: ${result.errors.firstOrNull()}"
                    }
                }
            }
        }
        return ReplayResult(timeline = timeline, aspectRatio = aspectRatio, warnings = warnings)
    }
}

/** Outcome of replaying the edit history. */
data class ReplayResult(
    val timeline: Timeline,
    val aspectRatio: AspectRatio,
    val warnings: List<String> = emptyList(),
)
