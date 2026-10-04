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
    // Settings' repositories win over a project's. The Kotlin/Wasm plugin adds
    // its own for Node, Yarn and Binaryen, which are declared below instead.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Tools for the browser build (web/app), from their release pages.
        ivy("https://nodejs.org/dist") {
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            patternLayout { artifact("version_[revision]/[module]-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
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
// The browser app: web/app, next to the core's WebAssembly build in web/core.
include(":webApp")
project(":webApp").projectDir = file("web/app")
