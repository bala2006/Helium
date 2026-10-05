pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Helium"

// ---- Core (platform independent) -------------------------------------------------
include(":core:common")
include(":core:model")
include(":core:network")

// ---- Deterministic editing domain -------------------------------------------------
include(":editor:domain")

// ---- AI orchestration ------------------------------------------------------------
include(":ai:tools")
include(":ai:provider")
include(":ai:agent")

// ---- Media pipeline ---------------------------------------------------------------
include(":media:engine")
include(":media:indexer")

// ---- Android UI / persistence -----------------------------------------------------
include(":core:database")
include(":core:ui")

// ---- Application ------------------------------------------------------------------
include(":app")
