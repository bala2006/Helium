package com.sekhar.helium.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One row per project.
 *
 * The project document itself is stored as JSON in [documentJson] rather than
 * exploded into tables. That is a deliberate trade-off:
 *
 * * The timeline is a deeply nested value object that changes on every model
 *   iteration; normalising it would mean a migration per new effect type.
 * * Correctness matters more than partial updates here — the whole document is
 *   written in one transaction, so a crash can never persist a half-applied edit.
 *
 * The flat columns exist only so the projects list can be queried and sorted
 * without deserialising every document.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val durationMs: Long,
    val sourceCount: Int,
    val clipCount: Int,
    val thumbnailPath: String?,
    val documentJson: String,
)

/**
 * Cached semantic index for one source, keyed by the source fingerprint.
 *
 * Keying on the fingerprint means re-importing the same file never re-analyses
 * it, and editing its metadata (which changes the id) does not throw the index
 * away.
 */
@Entity(tableName = "semantic_memory")
data class SemanticMemoryEntity(
    @PrimaryKey val fingerprint: String,
    val sourceId: String,
    val indexVersion: Int,
    val stages: String,
    val updatedAtEpochMs: Long,
    val documentJson: String,
)

/**
 * One row per AI request, used for usage accounting and hard cost limits.
 *
 * Deliberately contains no media, transcript text or prompts — only counts and
 * timings — so the telemetry table is safe to log and to sync.
 */
@Entity(tableName = "ai_usage_events", primaryKeys = ["id"])
data class AiUsageEventEntity(
    val id: String,
    val projectId: String,
    val timestampEpochMs: Long,
    val providerId: String,
    val modelId: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val reasoningTokens: Int,
    val imagesSent: Int,
    val toolCalls: Int,
    val toolFailures: Int,
    val latencyMs: Long,
    val succeeded: Boolean,
)

/** Aggregate token/image usage for one project. */
data class UsageTotals(
    val projectId: String,
    val requests: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val imagesSent: Int,
    val toolCalls: Int,
    val latencyMs: Long,
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ProjectEntity?

    @Query("SELECT * FROM projects ORDER BY updatedAtEpochMs DESC")
    suspend fun findAll(): List<ProjectEntity>

    @Upsert
    suspend fun upsert(entity: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE projects SET thumbnailPath = :path WHERE id = :id")
    suspend fun updateThumbnail(id: String, path: String?)
}

@Dao
interface SemanticMemoryDao {
    @Query("SELECT * FROM semantic_memory WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): SemanticMemoryEntity?

    @Query("SELECT * FROM semantic_memory WHERE sourceId = :sourceId ORDER BY updatedAtEpochMs DESC LIMIT 1")
    suspend fun findBySourceId(sourceId: String): SemanticMemoryEntity?

    @Upsert
    suspend fun upsert(entity: SemanticMemoryEntity)

    @Query("DELETE FROM semantic_memory WHERE sourceId = :sourceId")
    suspend fun deleteBySourceId(sourceId: String)
}

@Dao
interface AiUsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: AiUsageEventEntity)

    @Query(
        """
        SELECT projectId AS projectId,
               COUNT(*) AS requests,
               COALESCE(SUM(inputTokens), 0) AS inputTokens,
               COALESCE(SUM(outputTokens), 0) AS outputTokens,
               COALESCE(SUM(imagesSent), 0) AS imagesSent,
               COALESCE(SUM(toolCalls), 0) AS toolCalls,
               COALESCE(SUM(latencyMs), 0) AS latencyMs
        FROM ai_usage_events
        WHERE projectId = :projectId
        GROUP BY projectId
        """,
    )
    fun observeTotals(projectId: String): Flow<UsageTotals?>

    @Query("DELETE FROM ai_usage_events WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)
}

@Database(
    entities = [ProjectEntity::class, SemanticMemoryEntity::class, AiUsageEventEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class HeliumDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun semanticMemoryDao(): SemanticMemoryDao
    abstract fun aiUsageDao(): AiUsageDao

    companion object {
        const val NAME = "helium.db"
    }
}
