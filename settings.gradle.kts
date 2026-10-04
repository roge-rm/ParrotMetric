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

rootProject.name = "ParrotMetric"
// The design: sketches, their solver and the feature history. No UI; tested on the JVM.
include(":model")
// The screens and the input, shared by every platform.
include(":shared")
// The Android app, with the C++ core (core/) in its JNI library.
include(":app")
// The desktop app for Linux and Windows, with the same core built for each.
include(":desktop")
