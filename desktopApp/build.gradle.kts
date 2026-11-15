import org.gradle.internal.os.OperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
    implementation(libs.jbr.api)
}

compose.desktop {
    application {
        mainClass = "dev.pencilstub.mauvelauncher.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "dev.pencilstub.mauvelauncher"
            packageVersion = "1.0.0"
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// macOS Liquid Glass
//
// macOS only gives an app the current (Liquid Glass) look, both the window chrome/traffic lights and the system's
// glass rendering of the app icon, when
//   1. its main executable was linked against the macOS 26+ SDK, and
//   2. it runs as an .app bundle that LaunchServices recognises (otherwise there's no app icon to render).
// A plain `java` process meets neither: every JDK's launcher is linked against an older SDK, and even from inside a
// bundle, LaunchServices doesn't associate `java` with it when the JDK home is the bundle itself.
//
// Packaged builds: the jpackage launcher inside the .app is re-stamped as SDK 26 after `createDistributable`.
// Dev runs (`run`, and the Compose Hot Reload `hot*` tasks that IntelliJ's run configuration uses): we generate a
// small dev .app whose executable is a native launcher compiled here with the installed Xcode SDK. Like jpackage's
// launcher, it loads libjli from an unmodified JetBrains Runtime (needed for hot reload) and calls JLI_Launch, so it
// behaves exactly like `java`.
// ---------------------------------------------------------------------------------------------------------------

// Shell snippet that stamps the Mach-O at "$1" as built against the macOS 26 SDK
val stampSdk26 = """
    vtool="$(xcrun -f vtool)"
    "${'$'}vtool" -set-build-version macos 11.0 26.0 -replace -output "$1.tmp" "$1"
    mv "$1.tmp" "$1"
""".trimIndent()

abstract class LiquidGlassDevApp : ValueSource<String, LiquidGlassDevApp.Params> {
    interface Params : ValueSourceParameters {
        val javaHome: Property<String>
        val bundleDir: Property<String>
        val appName: Property<String>
        val bundleId: Property<String>
        val iconFile: Property<String>
    }

    @get:Inject abstract val exec: ExecOperations

    override fun obtain(): String {
        val p = parameters
        val bundle = File(p.bundleDir.get())
        val contents = bundle.resolve("Contents")
        val executableName = p.appName.get().filter { it.isLetterOrDigit() }
        val executable = contents.resolve("MacOS/$executableName")
        val iconFile = p.iconFile.orNull?.let(::File)
        val launcherSource = """
            #include <dlfcn.h>
            #include <stdio.h>
            typedef int (*JLI_Launch_t)(int, char **, int, const char **, int, const char **, const char *,
                                        const char *, const char *, const char *, unsigned char, unsigned char,
                                        unsigned char, int);
            int main(int argc, char **argv) {
                void *jli = dlopen(${cString(p.javaHome.get() + "/lib/libjli.dylib")}, RTLD_NOW | RTLD_GLOBAL);
                JLI_Launch_t launch = jli ? (JLI_Launch_t)dlsym(jli, "JLI_Launch") : NULL;
                if (!launch) { fprintf(stderr, "${p.appName.get()} dev launcher: %s\n", dlerror()); return 1; }
                /* As the JDK's `java` launcher does (its version strings are only used by its own -version handling). */
                return launch(argc, argv, 0, NULL, 0, NULL, "", "", "java", "openjdk", 0, 1, 0, 0);
            }
        """.trimIndent()
        val fingerprint = listOf(
            launcherSource, p.bundleId.get(), iconFile?.let { "${it.absolutePath}@${it.lastModified()}" },
        ).joinToString("\n")
        val marker = contents.resolve(".liquid-glass-fingerprint")
        if (executable.isFile && marker.isFile && marker.readText() == fingerprint) return executable.absolutePath

        bundle.deleteRecursively()
        executable.parentFile.mkdirs()
        exec.exec {
            commandLine("xcrun", "clang", "-O2", "-x", "c", "-", "-o", executable.absolutePath)
            standardInput = launcherSource.byteInputStream()
        }

        val icon = contents.resolve("Resources/AppIcon.icns").apply { parentFile.mkdirs() }
        if (iconFile != null) {
            iconFile.copyTo(icon, overwrite = true)
        } else {
            LiquidGlassDevApp::class.java.getResourceAsStream("/default-compose-desktop-icon-mac.icns")
                ?.use { input -> icon.outputStream().use { input.copyTo(it) } }
        }
        contents.resolve("Info.plist").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>CFBundleExecutable</key><string>$executableName</string>
                <key>CFBundleIdentifier</key><string>${p.bundleId.get()}</string>
                <key>CFBundleName</key><string>${p.appName.get()}</string>
                <key>CFBundlePackageType</key><string>APPL</string>
                <key>CFBundleIconFile</key><string>AppIcon</string>
                <key>NSHighResolutionCapable</key><true/>
            </dict>
            </plist>
            """.trimIndent()
        )
        exec.exec {
            commandLine(
                "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister",
                "-f", bundle.absolutePath,
            )
            isIgnoreExitValue = true
        }
        marker.writeText(fingerprint)
        return executable.absolutePath
    }

    private fun cString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

class BundledJavaLauncher(private val executable: RegularFile, private val jdk: JavaInstallationMetadata) : JavaLauncher {
    override fun getMetadata(): JavaInstallationMetadata = jdk
    override fun getExecutablePath(): RegularFile = executable
}

if (OperatingSystem.current().isMacOsX) {
    val jbrLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(21)
        vendor = JvmVendorSpec.JETBRAINS
    }
    compose.desktop.application.javaHome = jbrLauncher.get().metadata.installationPath.asFile.absolutePath

    val devAppExecutable = providers.of(LiquidGlassDevApp::class) {
        // Resolved to a plain string: the configuration cache can't restore a toolchain-backed provider here.
        parameters.javaHome = jbrLauncher.get().metadata.installationPath.asFile.absolutePath
        parameters.bundleDir = layout.buildDirectory.dir("liquid-glass/Mauve Launcher.app").map { it.asFile.absolutePath }
        parameters.appName = "Mauve Launcher"
        parameters.bundleId = "dev.pencilstub.mauvelauncher.dev"
        parameters.iconFile = compose.desktop.application.nativeDistributions.macOS.iconFile.map { it.asFile.absolutePath }
    }

    // -Xdock:name: otherwise AWT names the app after its main class ("MainKt") in the Dock and menu bar.
    val dockName = "-Xdock:name=Mauve Launcher"
    afterEvaluate {
        tasks.named<JavaExec>("run") {
            executable(devAppExecutable.get())
            jvmArgs(dockName)
        }
    }

    val projectDir = layout.projectDirectory
    tasks.withType<JavaExec>().matching { it.name.startsWith("hot") }.configureEach {
        javaLauncher = jbrLauncher.zip(devAppExecutable) { jbr, exe -> BundledJavaLauncher(projectDir.file(exe), jbr.metadata) }
        jvmArgs(dockName)
    }

    // The packaged .app's own launcher (from jpackage) has the same SDK problem: stamp it too, then re-sign
    tasks.matching { it.name == "createDistributable" }.configureEach {
        val appsDir = layout.buildDirectory.dir("compose/binaries/main/app").get().asFile
        val script = "set -eu\n$stampSdk26\ncodesign --force --deep --sign - \"$2\""
        doLast {
            appsDir.listFiles { f -> f.extension == "app" }!!.forEach { app ->
                val launcher = app.resolve("Contents/MacOS").listFiles()!!.single { it.isFile && it.canExecute() }
                val proc = ProcessBuilder("/bin/sh", "-c", script, "sh", launcher.absolutePath, app.absolutePath)
                    .inheritIO().start()
                check(proc.waitFor() == 0) { "Failed to stamp ${launcher.name} with the macOS 26 SDK" }
            }
        }
    }
}