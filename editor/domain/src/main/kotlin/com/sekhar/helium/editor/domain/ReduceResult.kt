package com.sekhar.helium.editor.domain

import com.sekhar.helium.core.model.EditOperation
import com.sekhar.helium.core.model.Timeline

/**
 * Outcome of reducing a single [EditOperation].
 *
 * A failure is always recoverable: the caller keeps the previous timeline, so a
 * transaction can never leave the project half-edited.
 */
sealed interface ReduceResult {

    val timeline: Timeline

    data class Success(
        override val timeline: Timeline,
        val warnings: List<String> = emptyList(),
        val notes: List<String> = emptyList(),
    ) : ReduceResult

    data class Failure(
        val operation: EditOperation,
        val errors: List<String>,
        override val timeline: Timeline = Timeline(),
    ) : ReduceResult
}
