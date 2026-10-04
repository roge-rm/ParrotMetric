// The screens and the input, shared by every platform. Android only for now;
// desktop and browser targets come later.
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

    sourceSets {
        commonMain.dependencies {
            implementation(libs.jb.compose.runtime)
            implementation(libs.jb.compose.foundation)
            implementation(libs.jb.compose.ui)
            implementation(libs.jb.compose.material3)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
