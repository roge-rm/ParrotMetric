import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// ParrotMetric for desktop Linux and Windows: the shared app in a window, with
// the core built by CMake from the same sources as Android (see native/).
//
//   ./gradlew :desktop:run             build the core for this machine and start it
//   ./gradlew :desktop:debAmd64        a .deb for Debian and Ubuntu on x86-64
//   ./gradlew :desktop:debArm64        a .deb for Raspberry Pi OS (64-bit)
//   ./gradlew :desktop:appImageAmd64   build/appimage/parrotmetric-<version>-x86_64.AppImage
//   ./gradlew :desktop:appImageArm64   build/appimage/parrotmetric-<version>-aarch64.AppImage
//   ./gradlew :desktop:windowsX64      build/windows/parrotmetric-<version>-setup.exe and a portable zip
//
// The Linux core is built once per architecture on Ubuntu 22.04
// (native/Dockerfile.linux) with the C++ runtime linked in, and the Debian
// package and the AppImage share it. The packages and the Windows build need
// Docker. Each build of OCCT for a new target takes a while; it's kept in
// core/build/occt after that.

kotlin { jvmToolchain(21) }

/** The version, read from app/build.gradle.kts where it's set. */
val appGradle = rootProject.file("app/build.gradle.kts").readText()
val versionName = Regex("versionName = \"([^\"]+)\"").find(appGradle)!!.groupValues[1]

val debAmd64Runtime: Configuration = configurations.create("debAmd64Runtime")
val debArm64Runtime: Configuration = configurations.create("debArm64Runtime")
val windowsX64Runtime: Configuration = configurations.create("windowsX64Runtime")

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.common)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jb.compose.material3)
    runtimeOnly(compose.desktop.currentOs)
    debAmd64Runtime(compose.desktop.linux_x64)
    debArm64Runtime(compose.desktop.linux_arm64)
    windowsX64Runtime(compose.desktop.windows_x64)
}

for (runtime in listOf(debAmd64Runtime, debArm64Runtime, windowsX64Runtime)) {
    runtime.extendsFrom(configurations.implementation.get())
    runtime.isCanBeConsumed = false
    // Resolved the same way as the run classpath, so :shared gives its desktop jar.
    val from = configurations.runtimeClasspath.get().attributes
    runtime.attributes {
        for (key in from.keySet()) {
            @Suppress("UNCHECKED_CAST")
            attribute(key as Attribute<Any>, from.getAttribute(key)!!)
        }
    }
}

val nativeDir = layout.projectDirectory.dir("native")
val nativeOut = layout.buildDirectory.dir("native")
val root: String = rootProject.projectDir.absolutePath
val coreSources = listOf(rootProject.file("core/src"), rootProject.file("core/CMakeLists.txt"), rootProject.file("app/src/main/cpp/jni.cpp"))

val ccache: Boolean = File("/usr/bin/ccache").canExecute()
val launcher = if (ccache) "-DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache" else ""
val sdkCmake = File(System.getProperty("user.home"), "Android/Sdk/cmake/4.1.2/bin").absolutePath

/** The core for this machine, for :desktop:run. OCCT is built for it first (core/build/occt/host). */
val buildCore = tasks.register<Exec>("buildCore") {
    inputs.files(coreSources)
    inputs.dir(nativeDir)
    outputs.dir(nativeOut.map { it.dir("host") })
    val out = nativeOut.get().dir("host").asFile.path
    environment("PATH", "$sdkCmake:${System.getenv("PATH")}")
    commandLine(
        "sh", "-c",
        "core/scripts/build-occt.sh host && " +
            "cmake -S desktop/native -B '$out' -G Ninja -DCMAKE_BUILD_TYPE=Release $launcher >/dev/null && cmake --build '$out' -j 10",
    )
    workingDir = rootProject.projectDir
}

/** Mounts and settings for a build container: the repo at the same path, and its compiler cache. */
fun containerArgs(image: String): String {
    val cache = File(System.getProperty("user.home"), ".cache/ccache-containers/$image")
    if (ccache) cache.mkdirs()
    return "-v '$root':'$root' -w '$root'" + if (ccache) " -v '$cache':/ccache -e CCACHE_DIR=/ccache" else ""
}

/** The command that runs [script] in the build container made from native/Dockerfile.[name]. */
fun inContainer(name: String, script: String) = listOf(
    "sh", "-c",
    "docker build -q -t parrotmetric-$name -f desktop/native/Dockerfile.$name desktop/native >/dev/null && " +
        "docker run --rm -u \$(id -u):\$(id -g) ${containerArgs(name)} -e HOME=/tmp parrotmetric-$name sh -c '$script'",
)

/**
 * The Linux core for one architecture, in the Ubuntu 22.04 container: OCCT
 * first (core/build/occt/linux-<arch>), then the JNI library.
 */
fun registerLinuxCore(arch: String, toolchain: String?): TaskProvider<Exec> {
    val cap = arch.replaceFirstChar { it.uppercase() }
    val out = nativeOut.get().dir("linux-$arch").asFile.path
    val tc = toolchain?.let { "$root/desktop/native/$it" }
    val static = "-static-libstdc++ -static-libgcc"
    return tasks.register<Exec>("buildCoreLinux$cap") {
        inputs.files(coreSources)
        inputs.dir(nativeDir)
        outputs.dir(out)
        workingDir = rootProject.projectDir
        commandLine(
            inContainer(
                "linux",
                "OCCT_CMAKE_ARGS=\"${launcher}\" core/scripts/build-occt.sh linux-$arch ${tc ?: ""} && " +
                    "J=/usr/lib/jvm/java-17-openjdk-amd64/include; " +
                    "cmake -S desktop/native -B $out -G Ninja -DCMAKE_BUILD_TYPE=Release -DPM_OCCT_TARGET=linux-$arch $launcher " +
                    (tc?.let { "-DCMAKE_TOOLCHAIN_FILE=$it " } ?: "") +
                    "\"-DCMAKE_SHARED_LINKER_FLAGS=$static\" \"-DJNI_INCLUDE_DIRS=\$J;\$J/linux\" >/dev/null && " +
                    "cmake --build $out -j 10",
            ),
        )
    }
}

val coreAmd64 = registerLinuxCore("amd64", null)
val coreArm64 = registerLinuxCore("arm64", "aarch64-linux-gnu.cmake")

/** The licence texts that go with every package. */
val licences = rootProject.file("licences")

compose.desktop {
    application {
        mainClass = "com.rm.parrotmetric.desktop.MainKt"
        jvmArgs += listOf("-Djava.library.path=${nativeOut.get().dir("host").asFile.absolutePath}", "--enable-native-access=ALL-UNNAMED")
    }
}
tasks.matching { it.name == "run" }.configureEach { dependsOn(buildCore) }

/** Copies the runtime's jars into [lib], named with their group, since two jars can share a file name. */
fun copyJars(runtime: Set<ResolvedArtifactResult>, lib: File, mode: String? = null) {
    lib.mkdirs()
    for (a in runtime) {
        val id = a.id.componentIdentifier
        val name = if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) "${id.group}-${a.file.name}" else a.file.name
        val to = File(lib, name)
        a.file.copyTo(to, overwrite = true)
        if (mode != null) Files.setPosixFilePermissions(to.toPath(), PosixFilePermissions.fromString(mode))
    }
}

// --- The Debian packages ------------------------------------------------------
//
// Jars in /usr/lib/parrotmetric/lib, the core in /usr/lib/parrotmetric/native,
// a launcher in /usr/bin, a menu entry and an icon. Built with dpkg-deb from a
// staged tree, owned by root.

fun registerDeb(arch: String, runtime: Configuration, core: TaskProvider<Exec>) {
    val cap = arch.replaceFirstChar { it.uppercase() }
    val stage = layout.buildDirectory.dir("deb/$arch")
    val coreDir = nativeOut.get().dir("linux-$arch")
    val stageTask = tasks.register<Sync>("stageDeb$cap") {
        dependsOn(core)
        into(stage)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        // Debian's file modes. The Gradle cache keeps jars private to their owner.
        filePermissions { unix("rw-r--r--") }
        dirPermissions { unix("rwxr-xr-x") }
        from(tasks.named("jar")) { into("usr/lib/parrotmetric/lib") }
        from(coreDir.file("libparrotmetric.so")) { into("usr/lib/parrotmetric/native") }
        from(file("deb/parrotmetric")) {
            into("usr/bin")
            filePermissions { unix("rwxr-xr-x") }
        }
        from(file("deb/parrotmetric.desktop")) { into("usr/share/applications") }
        from(rootProject.file("branding/parrotmetric-icon-256.png")) {
            into("usr/share/icons/hicolor/256x256/apps")
            rename { "parrotmetric.png" }
        }
        from(rootProject.file("LICENSE")) { into("usr/share/doc/parrotmetric") }
        from(licences) { into("usr/share/doc/parrotmetric/licences") }
    }
    val deb = layout.buildDirectory.file("deb/parrotmetric_${versionName}_$arch.deb")
    tasks.register("deb$cap") {
        group = "distribution"
        description = "Builds parrotmetric_${versionName}_$arch.deb"
        dependsOn(stageTask)
        val dir = stage.get().asFile
        val out = deb.get().asFile
        val template = file("deb/control")
        val version = versionName
        val artifacts = runtime.incoming.artifacts.resolvedArtifacts
        inputs.files(runtime)
        inputs.dir(stage)
        inputs.file(template)
        outputs.file(out)
        doLast {
            copyJars(artifacts.get(), File(dir, "usr/lib/parrotmetric/lib"), "rw-r--r--")
            Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
            val sizeKb = dir.walkTopDown().filter { it.isFile && !it.path.contains("/DEBIAN/") }.sumOf { it.length() } / 1024
            val control = File(dir, "DEBIAN/control")
            control.parentFile.mkdirs()
            control.writeText(template.readText().replace("@VERSION@", version).replace("@ARCH@", arch).replace("@SIZE@", sizeKb.toString()))
            val result = ProcessBuilder("dpkg-deb", "--root-owner-group", "--build", dir.path, out.path).redirectErrorStream(true).start()
            val said = result.inputStream.bufferedReader().readText()
            check(result.waitFor() == 0) { "dpkg-deb failed: $said" }
            // So it isn't staged in with the jars next time.
            control.parentFile.deleteRecursively()
        }
    }
}

registerDeb("amd64", debAmd64Runtime, coreAmd64)
registerDeb("arm64", debArm64Runtime, coreArm64)

// --- The AppImages --------------------------------------------------------------
//
// One file that runs on most Linux desktops. It carries a Java runtime
// (Eclipse Temurin) and the same core as the Debian package. Downloads are
// pinned and checked against their SHA-256.

class Download(val url: String, val sha256: String) {
    val name: String get() = url.substringAfterLast('/')
}
val appImageTool = Download(
    "https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage",
    "ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0",
)
val downloads = layout.buildDirectory.dir("downloads")

fun fetch(d: Download, into: File): File {
    val file = File(into, d.name)
    fun sum(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    if (file.isFile && sum(file) == d.sha256) return file
    into.mkdirs()
    URI(d.url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
    val got = sum(file)
    check(got == d.sha256) { "${d.name}: expected SHA-256 ${d.sha256}, got $got" }
    return file
}

/** Runs [command] and returns its output, failing the build with the output if it fails. */
fun runCommand(vararg command: String, env: Map<String, String> = emptyMap()): String {
    val process = ProcessBuilder(*command).redirectErrorStream(true).apply { environment().putAll(env) }.start()
    val said = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "${command.first().substringAfterLast('/')} failed: $said" }
    return said.trim()
}

fun registerAppImage(arch: String, appImageArch: String, runtime: Configuration, core: TaskProvider<Exec>, jre: Download, appImageRuntime: Download) {
    val cap = arch.replaceFirstChar { it.uppercase() }
    val coreDir = nativeOut.get().dir("linux-$arch")
    val appDir = layout.buildDirectory.dir("appimage/$arch/ParrotMetric.AppDir")
    val stage = tasks.register<Sync>("stageAppImage$cap") {
        dependsOn(core)
        into(appDir)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(tasks.named("jar")) { into("usr/lib/parrotmetric/lib") }
        from(coreDir.file("libparrotmetric.so")) { into("usr/lib/parrotmetric/native") }
        from(file("appimage/AppRun")) { filePermissions { unix("rwxr-xr-x") } }
        from(file("deb/parrotmetric.desktop"))
        from(rootProject.file("branding/parrotmetric-icon-256.png")) { rename { "parrotmetric.png" } }
        from(rootProject.file("LICENSE")) { into("usr/share/doc/parrotmetric") }
        from(licences) { into("usr/share/doc/parrotmetric/licences") }
        // The runtime is unpacked here later and replaced each build.
        preserve { include("usr/lib/parrotmetric/jre/**") }
    }
    val out = layout.buildDirectory.file("appimage/parrotmetric-$versionName-$appImageArch.AppImage")
    tasks.register("appImage$cap") {
        group = "distribution"
        description = "Builds parrotmetric-$versionName-$appImageArch.AppImage"
        notCompatibleWithConfigurationCache("uses the build script's download and exec helpers")
        dependsOn(stage)
        val artifacts = runtime.incoming.artifacts.resolvedArtifacts
        val dir = appDir.get().asFile
        val cache = downloads.get().asFile
        val image = out.get().asFile
        inputs.files(runtime)
        inputs.dir(appDir)
        outputs.file(image)
        doLast {
            copyJars(artifacts.get(), File(dir, "usr/lib/parrotmetric/lib"))
            val jreDir = File(dir, "usr/lib/parrotmetric/jre")
            jreDir.deleteRecursively()
            jreDir.mkdirs()
            runCommand("tar", "-xzf", fetch(jre, cache).path, "-C", jreDir.path, "--strip-components=1")
            val tool = fetch(appImageTool, cache).apply { setExecutable(true) }
            val runtimeFile = fetch(appImageRuntime, cache)
            image.delete()
            // Extract and run instead of mounting, so building doesn't need FUSE.
            runCommand(
                tool.path, "--no-appstream", "--runtime-file", runtimeFile.path, dir.path, image.path,
                env = mapOf("ARCH" to appImageArch, "APPIMAGE_EXTRACT_AND_RUN" to "1"),
            )
        }
    }
}

registerAppImage(
    "amd64", "x86_64", debAmd64Runtime, coreAmd64,
    Download(
        "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jre_x64_linux_hotspot_21.0.12.1_1.tar.gz",
        "2413149700df0f7d440500a84a8f764c535f21e5a5e87d38328b64eec2c5b500",
    ),
    Download(
        "https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-x86_64",
        "2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d",
    ),
)
registerAppImage(
    "arm64", "aarch64", debArm64Runtime, coreArm64,
    Download(
        "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jre_aarch64_linux_hotspot_21.0.12.1_1.tar.gz",
        "14be1f35ebdbd1f6e8d57eb911a3ffb74d6d9aa255abc5daf2b1302002cf2cf2",
    ),
    Download(
        "https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-aarch64",
        "00cbdfcf917cc6c0ff6d3347d59e0ca1f7f45a6df1a428a0d6d8a78664d87444",
    ),
)

// --- Windows -------------------------------------------------------------------
//
// 64-bit Windows 10 and 11, cross-built in a container with MinGW-w64
// (native/Dockerfile.windows): the core and the launcher, the same jars as
// Linux with Skia's Windows renderer, and Eclipse Temurin's Java runtime. It
// comes as an installer and a portable zip with the same files.

val coreWindows = tasks.register<Exec>("buildCoreWindows") {
    val out = nativeOut.get().dir("windows-x64").asFile.path
    val version = versionName
    inputs.files(coreSources)
    inputs.dir(nativeDir)
    inputs.dir(layout.projectDirectory.dir("windows"))
    inputs.property("version", version)
    outputs.dir(out)
    workingDir = rootProject.projectDir
    val tc = "$root/desktop/native/x86_64-w64-mingw32.cmake"
    commandLine(
        inContainer(
            "windows",
            "OCCT_CMAKE_ARGS=\"${launcher}\" core/scripts/build-occt.sh windows-x64 $tc && " +
                "J=/usr/lib/jvm/java-21-openjdk-amd64/include; " +
                "cmake -S desktop/native -B $out -G Ninja -DCMAKE_BUILD_TYPE=Release -DPM_OCCT_TARGET=windows-x64 -DCMAKE_TOOLCHAIN_FILE=$tc $launcher " +
                "\"-DJNI_INCLUDE_DIRS=\$J;$root/desktop/native/win32\" -DPM_VERSION=$version >/dev/null && " +
                "cmake --build $out -j 10",
        ),
    )
}

val windowsStage = layout.buildDirectory.dir("windows/ParrotMetric")
val stageWindows = tasks.register<Sync>("stageWindowsX64") {
    dependsOn(coreWindows)
    into(windowsStage)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val coreDir = nativeOut.get().dir("windows-x64")
    from(coreDir.file("ParrotMetric.exe"))
    from(coreDir.file("parrotmetric.dll")) { into("app") }
    from(tasks.named("jar")) { into("app/lib") }
    from(rootProject.file("LICENSE")) { rename { "LICENSE.txt" } }
    from(licences) { into("licences") }
    preserve { include("runtime/**", "app/lib/**") }
}

tasks.register("windowsX64") {
    group = "distribution"
    description = "Builds parrotmetric-$versionName-setup.exe and the portable zip for 64-bit Windows"
    notCompatibleWithConfigurationCache("uses the build script's download and exec helpers")
    dependsOn(stageWindows)
    val artifacts = windowsX64Runtime.incoming.artifacts.resolvedArtifacts
    val stage = windowsStage.get().asFile
    val out = layout.buildDirectory.dir("windows").get().asFile
    val cache = downloads.get().asFile
    val version = versionName
    val jre = Download(
        "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jre_x64_windows_hotspot_21.0.12.1_1.zip",
        "d35f31e712f0fcf6ac5a093edc90204fbff22f720ba3950bd09d331d5e621636",
    )
    inputs.files(windowsX64Runtime)
    inputs.dir(windowsStage)
    outputs.files(File(out, "parrotmetric-$version-setup.exe"), File(out, "parrotmetric-$version-windows-x64.zip"))
    doLast {
        copyJars(artifacts.get(), File(stage, "app/lib"))
        val zip = fetch(jre, cache)
        val runtime = File(stage, "runtime")
        runtime.deleteRecursively()
        val unpacked = File(out, "jre-unpacked").apply { deleteRecursively(); mkdirs() }
        runCommand("unzip", "-q", zip.path, "-d", unpacked.path)
        unpacked.listFiles()!!.single().renameTo(runtime)
        unpacked.delete()
        val setup = "parrotmetric-$version-setup.exe"
        val portable = "parrotmetric-$version-windows-x64.zip"
        File(out, setup).delete()
        File(out, portable).delete()
        runCommand(
            "docker", "run", "--rm", "-u", "${runCommand("id", "-u")}:${runCommand("id", "-g")}", "-v", "$root:$root",
            "-w", "$root/desktop/windows", "parrotmetric-windows", "sh", "-c",
            "makensis -V2 -DVERSION=$version -DSTAGE=${stage.path} -DOUT=${out.path}/$setup installer.nsi && " +
                "cd ${out.path} && zip -qr $portable ParrotMetric",
        )
    }
}

/** Writes the run classpath to build/classpath.txt, for starting the app with plain java while testing. */
tasks.register("classpathFile") {
    dependsOn(tasks.named("jar"))
    val jar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val cp = configurations.runtimeClasspath
    val out = layout.buildDirectory.file("classpath.txt")
    outputs.file(out)
    doLast { out.get().asFile.writeText((listOf(jar.get().asFile) + cp.get().files).joinToString(":")) }
}
