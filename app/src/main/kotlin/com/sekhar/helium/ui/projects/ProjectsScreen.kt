package com.sekhar.helium.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sekhar.helium.core.model.Project
import com.sekhar.helium.core.model.ProjectId
import com.sekhar.helium.core.model.ProjectSummary
import com.sekhar.helium.core.model.formatDurationMs
import com.sekhar.helium.core.ui.components.HeliumCard
import com.sekhar.helium.core.ui.components.HeliumChip
import com.sekhar.helium.core.ui.components.HeliumEmptyState
import com.sekhar.helium.core.ui.components.HeliumPrimaryButton
import com.sekhar.helium.core.ui.theme.HeliumColors
import com.sekhar.helium.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ProjectsUiState(
    val loading: Boolean = true,
    val projects: List<ProjectSummary> = emptyList(),
    val error: String? = null,
    val openProjectId: ProjectId? = null,
)

class ProjectsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ProjectsUiState())
    val state: StateFlow<ProjectsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.projectStore.observeProjects().collect { projects ->
                _state.value = _state.value.copy(loading = false, projects = projects, error = null)
            }
        }
    }

    fun createProject(name: String? = null) {
        viewModelScope.launch {
            val now = container.time.nowEpochMs()
            val project = Project.newProject(
                id = ProjectId(container.ids.newId()),
                name = name ?: defaultName(),
                nowEpochMs = now,
            )
            container.projectStore.saveProject(project)
            _state.value = _state.value.copy(openProjectId = project.id)
        }
    }

    /** Called by the screen once navigation to the new project has happened. */
    fun consumeOpenRequest() {
        _state.value = _state.value.copy(openProjectId = null)
    }

    fun deleteProject(projectId: ProjectId) {
        viewModelScope.launch {
            // Only Helium's own metadata is removed. The user's original media is
            // never deleted here — that requires a separate, explicit action.
            val summary = container.projectStore.deleteProject(projectId)
            if (summary == null) {
                _state.value = _state.value.copy(error = "That project no longer exists.")
            }
        }
    }

    private fun defaultName(): String {
        val formatter = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
        return "Project ${formatter.format(Date(container.time.nowEpochMs()))}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    state: ProjectsUiState,
    onNewProject: () -> Unit,
    onOpenProject: (ProjectId) -> Unit,
    onDeleteProject: (ProjectId) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRequestConsumed: () -> Unit,
) {
    LaunchedEffect(state.openProjectId) {
        state.openProjectId?.let { projectId ->
            onOpenProject(projectId)
            onOpenRequestConsumed()
        }
    }

    Scaffold(
        containerColor = HeliumColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Helium", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = HeliumColors.Background,
                    titleContentColor = HeliumColors.OnBackground,
                ),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Unit

                state.projects.isEmpty() -> HeliumEmptyState(
                    title = "No projects yet",
                    body = "Create a project, import a video and describe the edit in your own words.",
                    modifier = Modifier.fillMaxSize(),
                    action = {
                        HeliumPrimaryButton(text = "New project", onClick = onNewProject)
                    },
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        HeliumPrimaryButton(
                            text = "New project",
                            onClick = onNewProject,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    items(state.projects, key = { it.id.value }) { project ->
                        ProjectRow(
                            summary = project,
                            onOpen = { onOpenProject(project.id) },
                            onDelete = { onDeleteProject(project.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(
    summary: ProjectSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    HeliumCard(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = summary.name,
                style = MaterialTheme.typography.titleMedium,
                color = HeliumColors.OnBackground,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeliumChip(
                    text = "${summary.sourceCount} clip${if (summary.sourceCount == 1) "" else "s"}",
                    accent = HeliumColors.Plum,
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(
                    text = formatDurationMs(summary.durationMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = HeliumColors.OnSurfaceMuted,
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(
                    text = "${summary.clipCount} cut${if (summary.clipCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HeliumColors.OnSurfaceMuted,
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDelete) {
                Text("Delete project", color = HeliumColors.OnSurfaceFaint)
            }
        }
    }
}
