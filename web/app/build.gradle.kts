plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// ParrotMetric in a browser: Compose for Kotlin/Wasm with the app's shared
// code, calling the core built as WebAssembly in ../core.
//
//   ./gradlew :webApp:wasmJsBrowserDistribution    the page, in build/dist/wasmJs/productionExecutable

kotlin {
    wasmJs {
        outputModuleName.set("parrotmetric-ui")
        browser {
            commonWebpackConfig { outputFileName = "parrotmetric-ui.js" }
        }
        binaries.executable()
    }
    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.jb.compose.runtime)
            implementation(libs.jb.compose.foundation)
            implementation(libs.jb.compose.ui)
            implementation(libs.jb.compose.material3)
        }
    }
}

/** The core's WebAssembly, built by web/core/build.sh and served next to the page. */
val coreOut = rootProject.file("web/core/out")
val buildCore = tasks.register<Exec>("buildCore") {
    inputs.files(rootProject.file("core/src"), rootProject.file("core/CMakeLists.txt"), rootProject.file("app/src/main/cpp/jni.cpp"))
    inputs.files(fileTree(rootProject.file("web/core")) { exclude("build/**", "out/**") })
    outputs.dir(coreOut)
    commandLine(rootProject.file("web/core/build.sh").absolutePath)
}
kotlin.sourceSets.named("wasmJsMain") { resources.srcDir(files(coreOut).builtBy(buildCore)) }

/** The licence texts, served next to the page. */
val stageLicences = tasks.register<Sync>("stageLicences") {
    from(rootProject.file("LICENSE")) { rename { "gpl-3.0.txt" } }
    from(rootProject.file("licences"))
    into(layout.buildDirectory.dir("generated/licences/licences"))
}
kotlin.sourceSets.named("wasmJsMain") { resources.srcDir(stageLicences.map { it.destinationDir.parentFile }) }
