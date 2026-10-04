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
    }
}
