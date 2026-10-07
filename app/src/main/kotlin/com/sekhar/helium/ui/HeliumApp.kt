package com.sekhar.helium.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sekhar.helium.core.model.ProjectId
import com.sekhar.helium.di.AppContainer
import com.sekhar.helium.ui.editor.EditorScreen
import com.sekhar.helium.ui.editor.EditorViewModel
import com.sekhar.helium.ui.projects.ProjectsScreen
import com.sekhar.helium.ui.projects.ProjectsViewModel
import com.sekhar.helium.ui.settings.SettingsScreen
import com.sekhar.helium.ui.settings.SettingsViewModel

/** Routes. Deliberately data-free except for the project id. */
object Routes {
    const val PROJECTS = "projects"
    const val SETTINGS = "settings"
    const val EDITOR = "editor/{projectId}"

    fun editor(projectId: ProjectId) = "editor/${projectId.value}"
}

@Composable
fun HeliumApp(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    val projectsFactory = remember(container) {
        viewModelFactory {
            initializer { ProjectsViewModel(container) }
        }
    }
    val settingsFactory = remember(container) {
        viewModelFactory {
            initializer { SettingsViewModel(container) }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.PROJECTS,
    ) {
        composable(Routes.PROJECTS) {
            val viewModel: ProjectsViewModel = viewModel(factory = projectsFactory)
            val state by viewModel.state.collectAsStateWithLifecycle()
            ProjectsScreen(
                state = state,
                onNewProject = viewModel::createProject,
                onOpenProject = { navController.navigate(Routes.editor(it)) },
                onDeleteProject = viewModel::deleteProject,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenRequestConsumed = viewModel::consumeOpenRequest,
            )
        }

        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId").orEmpty()
            val editorFactory = remember(container, projectId) {
                viewModelFactory {
                    initializer { EditorViewModel(container, ProjectId(projectId)) }
                }
            }
            val viewModel: EditorViewModel = viewModel(factory = editorFactory)
            val state by viewModel.state.collectAsStateWithLifecycle()
            EditorScreen(
                state = state,
                onBack = { navController.popBackStack() },
                onImportUris = viewModel::importUris,
                onPromptChange = viewModel::onPromptChange,
                onPrompt = viewModel::submitPrompt,
                onCancelPrompt = viewModel::cancelPrompt,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onSeek = viewModel::seekTo,
                onPositionChanged = viewModel::onPlayerPosition,
                onTogglePlay = viewModel::togglePlay,
                onSplitAtPlayhead = viewModel::splitAtPlayhead,
                onSelectClip = viewModel::selectClip,
                onZoomChange = viewModel::setZoom,
                onExport = viewModel::startExport,
                onCancelExport = viewModel::cancelExport,
                onExportPresetChange = viewModel::setExportPreset,
                onShareExport = viewModel::shareLastExport,
                onDismissExportState = viewModel::dismissExportState,
                onDismissMessage = viewModel::dismissMessage,
            )
        }

        composable(Routes.SETTINGS) {
            val viewModel: SettingsViewModel = viewModel(factory = settingsFactory)
            val state by viewModel.state.collectAsStateWithLifecycle()
            SettingsScreen(
                state = state,
                onBack = { navController.popBackStack() },
                onBackendUrlChange = viewModel::setBackendUrl,
                onModelIdChange = viewModel::setModelId,
                onReasoningChange = viewModel::setReasoningLevel,
                onImageDetailChange = viewModel::setImageDetail,
                onCloudModeChange = viewModel::setCloudAnalysisMode,
                onMaxIterationsChange = viewModel::setMaxToolIterations,
                onTelemetryChange = viewModel::setTelemetry,
                onProxyPreviewChange = viewModel::setUseProxyForPreview,
                onAutoCaptionChange = viewModel::setAutoCaptionOnImport,
                onDefaultPresetChange = viewModel::setDefaultExportPreset,
                onCheckBackend = viewModel::checkBackend,
                onDismissMessage = viewModel::dismissMessage,
            )
        }
    }
}
