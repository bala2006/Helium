package com.sekhar.helium.core.model

import kotlinx.serialization.Serializable

/** Where an edit came from. */
@Serializable
enum class TransactionSource { AI, MANUAL, SYSTEM }

/** Lifecycle of a transaction inside the edit history. */
@Serializable
enum class TransactionStatus { APPLIED, FAILED }

/**
 * An atomic group of [EditOperation]s produced by a single user instruction.
 *
 * Transactions are the unit of undo/redo and of user-visible summarisation.
 * Because the timeline is recomputed by replaying the applied transactions, a
 * failure while validating an operation leaves the project exactly as it was —
 * a half-applied transaction can never be observed.
 */
@Serializable
data class EditTransaction(
    val id: String,
    val index: Long,
    val userPrompt: String?,
    val summary: String,
    val operations: List<EditOperation>,
    val createdAtEpochMs: Long,
    val source: TransactionSource = TransactionSource.AI,
    val status: TransactionStatus = TransactionStatus.APPLIED,
) {
    val isApplied: Boolean get() = status == TransactionStatus.APPLIED
}

/**
 * Ordered list of transactions plus the undo cursor.
 *
 * `cursor` is the number of transactions currently applied: `transactions`
 * `[0, cursor)` are live, `[cursor, size)` are undone and can be redone. The
 * timeline is always `reduce(transactions[0 until cursor])`, which makes undo
 * exact and cheap — no inverse operations are ever recorded.
 */
@Serializable
data class EditHistory(
    val transactions: List<EditTransaction> = emptyList(),
    val cursor: Int = 0,
) {
    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < transactions.size

    /** Transactions that are currently applied. */
    val appliedTransactions: List<EditTransaction>
        get() = transactions.subList(0, cursor.coerceIn(0, transactions.size))

    /** Transactions that were undone and could be redone. */
    val undoneTransactions: List<EditTransaction>
        get() = transactions.subList(cursor.coerceIn(0, transactions.size), transactions.size)

    val nextIndex: Long get() = cursor.toLong()

    /**
     * Appends [transaction] and moves the cursor to the end.
     *
     * Any undone transactions are discarded first: making a new edit after
     * undoing is a new branch of history in every editor, and keeping the old
     * redo stack would let a stale timeline reappear.
     */
    fun append(transaction: EditTransaction): EditHistory {
        val kept = transactions.subList(0, cursor.coerceIn(0, transactions.size))
        return EditHistory(kept + transaction, kept.size + 1)
    }

    /** Moves the cursor back by [count]; returns the same history when impossible. */
    fun undo(count: Int = 1): EditHistory =
        EditHistory(transactions, (cursor - count).coerceIn(0, transactions.size))

    /** Moves the cursor forward by [count]. */
    fun redo(count: Int = 1): EditHistory =
        EditHistory(transactions, (cursor + count).coerceIn(0, transactions.size))

    /** Removes a transaction and keeps the cursor consistent. */
    fun remove(transactionId: String): EditHistory {
        val target = transactions.indexOfFirst { it.id == transactionId }
        if (target < 0) return this
        val newTransactions = transactions.filterIndexed { i, _ -> i != target }
        val newCursor = when {
            target < cursor -> cursor - 1
            else -> cursor
        }
        return EditHistory(newTransactions, newCursor.coerceIn(0, newTransactions.size))
    }
}

/** Result of attempting to apply a transaction. */
@Serializable
data class EditOutcome(
    val success: Boolean,
    val summary: String,
    val transaction: EditTransaction? = null,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val appliedOperationCount: Int = 0,
) {
    companion object {
        fun failure(errors: List<String>): EditOutcome = EditOutcome(
            success = false,
            summary = errors.firstOrNull() ?: "Edit failed",
            errors = errors,
        )
    }
}
