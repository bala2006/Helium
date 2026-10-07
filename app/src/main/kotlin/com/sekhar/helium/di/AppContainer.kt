package com.sekhar.helium.di

import android.content.Context
import com.sekhar.helium.ai.agent.AgentToolDispatcher
import com.sekhar.helium.ai.agent.VideoEditAgent
import com.sekhar.helium.ai.provider.AiModelProvider
import com.sekhar.helium.ai.provider.AiProviderRegistry
import com.sekhar.helium.ai.provider.BackendAiModelProvider
import com.sekhar.helium.core.common.AppDispatchers
import com.sekhar.helium.core.common.DefaultAppDispatchers
import com.sekhar.helium.core.common.HeliumLog
import com.sekhar.helium.core.common.IdGenerator
import com.sekhar.helium.core.common.PrintLog
import com.sekhar.helium.core.common.SystemTimeProvider
import com.sekhar.helium.core.common.TimeProvider
import com.sekhar.helium.core.common.UuidIdGenerator
import com.sekhar.helium.core.database.DataStoreSettingsStore
import com.sekhar.helium.core.database.HeliumDatabase
import com.sekhar.helium.core.database.HeliumDatabaseFactory
import com.sekhar.helium.core.database.ProjectStore
import com.sekhar.helium.core.database.RoomProjectStore
import com.sekhar.helium.core.database.RoomSemanticMemoryStore
import com.sekhar.helium.core.database.RoomUsageStore
import com.sekhar.helium.core.database.SemanticMemoryStore
import com.sekhar.helium.core.database.SettingsStore
import com.sekhar.helium.core.database.UsageStore
import com.sekhar.helium.core.network.GatewayClient
import com.sekhar.helium.core.network.KtorGatewayClient
import com.sekhar.helium.core.network.heliumHttpClient
import com.sekhar.helium.editor.domain.EditEngine
import com.sekhar.helium.media.engine.Media3VideoEngine
import com.sekhar.helium.media.engine.VideoEngine
import com.sekhar.helium.media.indexer.MediaSignalExtractor
import com.sekhar.helium.media.indexer.SemanticIndexer
import com.sekhar.helium.media.indexer.TranscriptionProvider
import com.sekhar.helium.media.indexer.UnavailableTranscriptionProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The app's dependency graph.
 *
 * An interface rather than a concrete class so instrumented tests and
 * previews can supply deterministic fakes without a DI framework.
 */
interface AppContainer {
    val appContext: Context
    val dispatchers: AppDispatchers
    val log: HeliumLog
    val time: TimeProvider
    val ids: IdGenerator

    val database: HeliumDatabase
    val projectStore: ProjectStore
    val semanticMemoryStore: SemanticMemoryStore
    val usageStore: UsageStore
    val settingsStore: SettingsStore

    val httpClient: HttpClient
    val gatewayClient: GatewayClient
    val aiProviders: AiProviderRegistry
    val transcriptionProvider: TranscriptionProvider

    val editEngine: EditEngine
    val videoEngine: VideoEngine
    val signalExtractor: MediaSignalExtractor
    val semanticIndexer: SemanticIndexer

    fun createAgent(dispatcher: AgentToolDispatcher): VideoEditAgent
}

/** Production wiring. */
class DefaultAppContainer(override val appContext: Context) : AppContainer {

    override val dispatchers: AppDispatchers = DefaultAppDispatchers
    override val log: HeliumLog = PrintLog()
    override val time: TimeProvider = SystemTimeProvider
    override val ids: IdGenerator = UuidIdGenerator

    override val database: HeliumDatabase by lazy { HeliumDatabaseFactory.create(appContext) }
    override val projectStore: ProjectStore by lazy { RoomProjectStore(database, time, ids) }
    override val semanticMemoryStore: SemanticMemoryStore by lazy { RoomSemanticMemoryStore(database, time) }
    override val usageStore: UsageStore by lazy { RoomUsageStore(database) }
    override val settingsStore: SettingsStore by lazy { DataStoreSettingsStore(appContext) }

    override val httpClient: HttpClient by lazy { heliumHttpClient(OkHttp.create()) }

    /**
     * Keeps the gateway address in sync with the user's settings.
     *
     * The URL is user-configurable so a self-hosted gateway needs no app update.
     * There is deliberately no provider key anywhere in the app — only the
     * gateway holds credentials.
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    private var currentBackendUrl: String = ""

    init {
        scope.launch {
            settingsStore.settings.collect { currentBackendUrl = it.backendBaseUrl }
        }
    }

    override val gatewayClient: GatewayClient by lazy {
        KtorGatewayClient(
            client = httpClient,
            baseUrlProvider = { currentBackendUrl },
            dispatchers = dispatchers,
            log = log,
        )
    }

    override val aiProviders: AiProviderRegistry by lazy {
        AiProviderRegistry(listOf<AiModelProvider>(BackendAiModelProvider(gatewayClient, log)))
    }

    override val transcriptionProvider: TranscriptionProvider = UnavailableTranscriptionProvider

    override val editEngine: EditEngine by lazy { EditEngine(ids = ids, time = time) }

    override val videoEngine: VideoEngine by lazy { Media3VideoEngine(appContext, dispatchers) }

    override val signalExtractor: MediaSignalExtractor by lazy { MediaSignalExtractor(appContext, dispatchers) }

    override val semanticIndexer: SemanticIndexer by lazy {
        SemanticIndexer(
            context = appContext,
            extractor = signalExtractor,
            store = semanticMemoryStore,
            transcriptionProvider = transcriptionProvider,
            ids = ids,
            time = time,
            dispatchers = dispatchers,
        )
    }

    override fun createAgent(dispatcher: AgentToolDispatcher): VideoEditAgent =
        VideoEditAgent(provider = aiProviders.available.first(), dispatcher = dispatcher, ids = ids, log = log)
}
