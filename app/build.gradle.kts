import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** The 32-bit build, for tablets like the Fire HD 8: see `release` below. */
val arm32 = project.hasProperty("arm32")
// Releases are ARM only. Debug builds add x86, to run on an x86 emulator.
val armAbi = if (arm32) "armeabi-v7a" else "arm64-v8a"
val emulatorAbi = if (arm32) "x86" else "x86_64"
val abis = listOf(armAbi, emulatorAbi)

android {
    namespace = "com.rm.parrotmetric"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rm.parrotmetric"
        minSdk = 27
        targetSdk = 37
        // Two APKs per release: 64-bit, and with -Parm32 a 32-bit one. A store
        // installs the highest versionCode a device can run, and most 64-bit
        // phones also run 32-bit code, so the 64-bit APK must be higher: the
        // release number times ten, plus 2 for 64-bit and 1 for 32-bit.
        // Bump [release], not the code.
        val release = 14
        versionCode = release * 10 + if (arm32) 1 else 2
        versionName = "0.6.0"
        ndk { abiFilters += armAbi }
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
    /**
     * Release signing, from a properties file in the sibling Keys/ folder,
     * outside the repository. Without it the release build comes out unsigned.
     * Losing the keystore means installed copies can never be updated, so keep
     * it backed up.
     */
    val keystoreProps = rootProject.file("../Keys/parrotmetric-keystore.properties")
    val signing: Properties? = if (keystoreProps.exists()) {
        Properties().also { p -> keystoreProps.inputStream().use { p.load(it) } }
    } else {
        null
    }
    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            ndk { abiFilters += emulatorAbi }
        }
        release {
            // Shrunk and optimised. What must be kept is in proguard-rules.pro.
            optimization {
                enable = true
                keepRules {
                    files.add(file("proguard-rules.pro"))
                }
            }
            if (signing != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    // The native library compressed in the APK, which more than halves the
    // download. Android unpacks it on install.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
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
        // -O2 for the ARM builds that ship: about 9% smaller than -O3 and
        // within a few percent of its speed on the Fire HD 8. -Os was 45%
        // slower at shelling.
        if (abi.startsWith("arm")) environment("OCCT_OPT", "-O2")
        // Only locals in the lambda, so the configuration cache can store it.
        val ndk = ndkPath
        argumentProviders.add(CommandLineArgumentProvider { listOf(abi, ndk.get()) })
    }
}
tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn(occtTasks)
}
