// The screens and the input, shared by every platform, and the app around
// them (app/). Android and desktop reach the core through the same JNI object,
// whose source is in src/jvmShared and compiled into both.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "com.rm.parrotmetric.shared"
        compileSdk = 37
        minSdk = 27
    }

    jvm("desktop")
    wasmJs { browser() }

    sourceSets {
        androidMain { kotlin.srcDir("src/jvmShared/kotlin") }
        getByName("desktopMain") { kotlin.srcDir("src/jvmShared/kotlin") }
        commonMain.dependencies {
            api(project(":model"))
            api(libs.jb.compose.runtime)
            api(libs.jb.compose.foundation)
            api(libs.jb.compose.ui)
            implementation(libs.jb.compose.material3)
            implementation(libs.kotlinx.coroutines.core)
        }
        getByName("desktopTest").dependencies { implementation(kotlin("test")) }
    }
}

/** The version, from app/build.gradle.kts where it's set, as APP_VERSION for every platform. */
val appVersion = tasks.register("appVersion") {
    val out = layout.buildDirectory.dir("generated/appVersion")
    val name = Regex("versionName = \"([^\"]+)\"").find(rootProject.file("app/build.gradle.kts").readText())!!.groupValues[1]
    inputs.property("version", name)
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/rm/parrotmetric/app/AppVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package com.rm.parrotmetric.app\n\nconst val APP_VERSION = \"$name\"\n")
    }
}
kotlin.sourceSets.commonMain { kotlin.srcDir(appVersion) }
