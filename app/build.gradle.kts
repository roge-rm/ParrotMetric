plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val abis = listOf("arm64-v8a", "x86_64")

android {
    namespace = "com.rm.parrotmetric"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rm.parrotmetric"
        minSdk = 27
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
        ndk { abiFilters += abis }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}

// OCCT is built once per ABI into core/build/occt by core/scripts/build-occt.sh,
// before CMake configures the JNI library. It's skipped when already built.
val ndkPath: Provider<String> = androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath }
val occtTasks = abis.map { abi ->
    tasks.register<Exec>("buildOcct-$abi") {
        group = "build"
        description = "Builds OCCT for $abi"
        executable(rootProject.file("core/scripts/build-occt.sh").absolutePath)
        // The ABIs build side by side, so each gets half of a build's 10 workers.
        environment("OCCT_JOBS", (10 / abis.size).coerceAtLeast(1).toString())
        // Only locals in the lambda, so the configuration cache can store it.
        val ndk = ndkPath
        argumentProviders.add(CommandLineArgumentProvider { listOf(abi, ndk.get()) })
    }
}
tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn(occtTasks)
}
