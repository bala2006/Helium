package com.sekhar.helium.core.database

import android.content.Context
import androidx.room.Room
import com.sekhar.helium.core.common.HeliumJson
import com.sekhar.helium.core.common.IdGenerator
import com.sekhar.helium.core.common.TimeProvider
import com.sekhar.helium.core.common.UuidIdGenerator
import com.sekhar.helium.core.model.AnalysisStage
import com.sekhar.helium.core.model.AiUsage
import com.sekhar.helium.core.model.AiTokenUsage
import com.sekhar.helium.core.model.Project
import com.sekhar.helium.core.model.ProjectId
import com.sekhar.helium.core.model.ProjectSummary
import com.sekhar.helium.core.model.SEMANTIC_INDEX_VERSION
import com.sekhar.helium.core.model.SemanticVideoMemory
import com.sekhar.helium.core.model.SourceId
import com.sekhar.helium.core.model.buildBaseTimeline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Pure conversions between the domain model and its persisted forms.
 *
 * Kept free of Android types so it is fully unit testable on the JVM.
 */
object ProjectDocuments {

    fun encode(project: Project): String = HeliumJson.encodeToString(project)

    /**
     * Returns `null` for a document that cannot be read.
     *
     * A corrupt project must degrade to "this project is unavailable" rather than
     * crashing the projects list, and must never be silently overwritten.
     */
    fun decodeOrNull(json: String): Project? =
        runCatching { HeliumJson.decodeFromString<Project>(json) }.getOrNull()

    /**
     * Fills in derived state that an older document may not have.
     *
     * [Project.baseTimeline] was added after the first documents were written, so
     * a project that has sources but no base timeline is repaired from those
     * sources instead of being discarded.
     */
    fun repair(project: Project, ids: IdGenerator = UuidIdGenerator): Project {
        if (project.baseTimeline.isEmpty && project.sources.isNotEmpty()) {
            return project.copy(baseTimeline = buildBaseTimeline(project.sources, ids))
        }
        return project
    }

    fun toEntity(project: Project, thumbnailPath: String?): ProjectEntity = ProjectEntity(
        id = project.id.value,
        name = project.name,
        createdAtEpochMs = project.createdAtEpochMs,
        updatedAtEpochMs = project.updatedAtEpochMs,
        durationMs = project.timeline.durationMs,
        sourceCount = project.sources.size,
        clipCount = project.timeline.allClips().size,
        thumbnailPath = thumbnailPath,
        documentJson = encode(project),
    )

    fun toSummary(entity: ProjectEntity): ProjectSummary = ProjectSummary(
        id = ProjectId(entity.id),
        name = entity.name,
        updatedAtEpochMs = entity.updatedAtEpochMs,
        durationMs = entity.durationMs,
        sourceCount = entity.sourceCount,
        clipCount = entity.clipCount,
        thumbnailPath = entity.thumbnailPath,
    )

    fun stagesToStorage(stages: Set<AnalysisStage>): String =
        stages.joinToString(",") { it.name }

    fun stagesFromStorage(raw: String): Set<AnalysisStage> =
        raw.split(',').mapNotNull { name ->
            AnalysisStage.entries.firstOrNull { it.name == name.trim() }
        }.toSet()
}

/** Reads and writes projects. */
interface ProjectStore {
    fun observeProjects(): Flow<List<ProjectSummary>>
    suspend fun loadProject(id: ProjectId): Project?
    suspend fun listProjectIds(): List<ProjectId>
    suspend fun saveProject(project: Project, thumbnailPath: String? = null)
    suspend fun updateThumbnail(id: ProjectId, path: String?)
    suspend fun deleteProject(id: ProjectId): ProjectSummary?
}

/**
 * Room-backed [ProjectStore].
 *
 * Deleting a project removes only Helium's own metadata. The user's original
 * media is never touched here — removing it requires an explicit, separate user
 * action, which is why [deleteProject] returns the summary so the caller can
 * decide what else to clean.
 */
class RoomProjectStore(
    private val database: HeliumDatabase,
    private val time: TimeProvider,
    private val ids: IdGenerator = UuidIdGenerator,
) : ProjectStore {

    override fun observeProjects(): Flow<List<ProjectSummary>> =
        database.projectDao().observeAll().map { entities ->
            entities.map(ProjectDocuments::toSummary)
        }

    override suspend fun loadProject(id: ProjectId): Project? {
        val entity = database.projectDao().findById(id.value) ?: return null
        val project = ProjectDocuments.decodeOrNull(entity.documentJson) ?: return null
        return ProjectDocuments.repair(project, ids)
    }

    override suspend fun listProjectIds(): List<ProjectId> =
        database.projectDao().findAll().map { ProjectId(it.id) }

    override suspend fun saveProject(project: Project, thumbnailPath: String?) {
        val existingThumbnail = database.projectDao().findById(project.id.value)?.thumbnailPath
        val entity = ProjectDocuments.toEntity(project, thumbnailPath ?: existingThumbnail)
        database.projectDao().upsert(entity)
    }

    override suspend fun updateThumbnail(id: ProjectId, path: String?) {
        database.projectDao().updateThumbnail(id.value, path)
    }

    override suspend fun deleteProject(id: ProjectId): ProjectSummary? {
        val entity = database.projectDao().findById(id.value) ?: return null
        val summary = ProjectDocuments.toSummary(entity)
        database.projectDao().delete(id.value)
        database.aiUsageDao().deleteForProject(id.value)
        return summary
    }

    /** Convenience for callers that want a fresh updatedAt after an edit. */
    suspend fun touch(project: Project): Project =
        project.copy(updatedAtEpochMs = time.nowEpochMs()).also { saveProject(it) }
}

/** Caches semantic indexes so a source is only ever analysed once. */
interface SemanticMemoryStore {
    suspend fun load(fingerprint: String): SemanticVideoMemory?
    suspend fun loadForSource(sourceId: SourceId): SemanticVideoMemory?
    suspend fun save(memory: SemanticVideoMemory)
    suspend fun deleteForSource(sourceId: SourceId)
}

class RoomSemanticMemoryStore(
    private val database: HeliumDatabase,
    private val time: TimeProvider,
) : SemanticMemoryStore {

    override suspend fun load(fingerprint: String): SemanticVideoMemory? {
        val entity = database.semanticMemoryDao().findByFingerprint(fingerprint) ?: return null
        if (entity.indexVersion != SEMANTIC_INDEX_VERSION) return null
        return runCatching { HeliumJson.decodeFromString<SemanticVideoMemory>(entity.documentJson) }
            .getOrNull()
    }

    override suspend fun loadForSource(sourceId: SourceId): SemanticVideoMemory? {
        val entity = database.semanticMemoryDao().findBySourceId(sourceId.value) ?: return null
        return runCatching { HeliumJson.decodeFromString<SemanticVideoMemory>(entity.documentJson) }
            .getOrNull()
    }

    override suspend fun save(memory: SemanticVideoMemory) {
        database.semanticMemoryDao().upsert(
            SemanticMemoryEntity(
                fingerprint = memory.fingerprint,
                sourceId = memory.sourceId.value,
                indexVersion = SEMANTIC_INDEX_VERSION,
                stages = ProjectDocuments.stagesToStorage(memory.stages),
                updatedAtEpochMs = time.nowEpochMs(),
                documentJson = HeliumJson.encodeToString(memory),
            ),
        )
    }

    override suspend fun deleteForSource(sourceId: SourceId) {
        database.semanticMemoryDao().deleteBySourceId(sourceId.value)
    }
}

/** Records AI usage for accounting and hard limits. */
interface UsageStore {
    fun observeTotals(projectId: ProjectId): Flow<AiUsage>
    suspend fun record(event: UsageEvent)
    suspend fun clear(projectId: ProjectId)
}

/** One AI request's counters. Contains no media and no prompt text. */
data class UsageEvent(
    val id: String,
    val projectId: ProjectId,
    val timestampEpochMs: Long,
    val providerId: String,
    val modelId: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val reasoningTokens: Int = 0,
    val imagesSent: Int = 0,
    val toolCalls: Int = 0,
    val toolFailures: Int = 0,
    val latencyMs: Long = 0L,
    val succeeded: Boolean = true,
)

class RoomUsageStore(private val database: HeliumDatabase) : UsageStore {

    override fun observeTotals(projectId: ProjectId): Flow<AiUsage> =
        database.aiUsageDao().observeTotals(projectId.value).map { totals ->
            if (totals == null) {
                AiUsage()
            } else {
                AiUsage(
                    tokens = AiTokenUsage(
                        inputTokens = totals.inputTokens,
                        outputTokens = totals.outputTokens,
                    ),
                    imagesSent = totals.imagesSent,
                    toolCalls = totals.toolCalls,
                    requests = totals.requests,
                    latencyMs = totals.latencyMs,
                )
            }
        }

    override suspend fun record(event: UsageEvent) {
        database.aiUsageDao().insert(
            AiUsageEventEntity(
                id = event.id,
                projectId = event.projectId.value,
                timestampEpochMs = event.timestampEpochMs,
                providerId = event.providerId,
                modelId = event.modelId,
                inputTokens = event.inputTokens,
                outputTokens = event.outputTokens,
                reasoningTokens = event.reasoningTokens,
                imagesSent = event.imagesSent,
                toolCalls = event.toolCalls,
                toolFailures = event.toolFailures,
                latencyMs = event.latencyMs,
                succeeded = event.succeeded,
            ),
        )
    }

    override suspend fun clear(projectId: ProjectId) {
        database.aiUsageDao().deleteForProject(projectId.value)
    }
}

/** Builds the singleton database. */
object HeliumDatabaseFactory {

    fun create(context: Context): HeliumDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            HeliumDatabase::class.java,
            HeliumDatabase.NAME,
        ).build()
}
