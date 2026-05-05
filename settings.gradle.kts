pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Forge"

include(":app")

// Pure-Kotlin core
include(":domain")
include(":core:common")

// Android core
include(":core:hardware")
include(":core:ui")

// Feature UI modules
include(":feature:discover")
include(":feature:catalog")
include(":feature:library")
include(":feature:chat")
include(":feature:compare")
include(":feature:workflows")
include(":feature:settings")

// Data layer
include(":data:catalog")
include(":data:discovery")
include(":data:download")
include(":data:storage")
include(":data:agents")

// Runtime layer
include(":runtime:core")
include(":runtime:llamacpp")
include(":runtime:mediapipe")
include(":runtime:executorch")
include(":runtime:mlc")
include(":runtime:registry")

// Agent layer
include(":agent:core")
include(":agent:orchestrator")
include(":agent:tools")
include(":agent:memory")
include(":agent:curator")
