package com.sekhar.helium.editor.domain

import com.sekhar.helium.core.model.EditOperation
import com.sekhar.helium.core.model.Timeline

/**
 * Outcome of reducing a single [EditOperation].
 *
 * A failure carries no timeline at all — the caller keeps the timeline it
 * already had, so a transaction can never leave the project half-edited.
 */
sealed interface ReduceResult {

    data class Success(
        val timeline: Timeline,
        val warnings: List<String> = emptyList(),
        val notes: List<String> = emptyList(),
    ) : ReduceResult

    data class Failure(
        val operation: EditOperation,
        val errors: List<String>,
    ) : ReduceResult
}
