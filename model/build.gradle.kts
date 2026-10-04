// The design itself: sketches and their constraint solver, and later the
// feature history. Plain Kotlin with no UI or platform code, so it's tested on
// the JVM: ./gradlew :model:jvmTest
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    jvm()

    android {
        namespace = "com.rm.parrotmetric.model"
        compileSdk = 37
        minSdk = 27
    }

    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
