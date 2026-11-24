import org.gradle.internal.os.OperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val jbrLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(21)
    vendor = JvmVendorSpec.JETBRAINS
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(21)
        vendor = JvmVendorSpec.JETBRAINS
    }
}

val iconsDir = layout.projectDirectory.dir("icons")
val iconComposerFile: File? = iconsDir.asFile.listFiles { f -> f.isDirectory && f.extension == "icon" }?.singleOrNull()

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
        javaHome = jbrLauncher.get().metadata.installationPath.asFile.absolutePath

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "dev.pencilstub.mauvelauncher"
            packageVersion = "1.0.0"

            fun icon(name: String) = iconsDir.file(name).asFile.takeIf { it.isFile }
            macOS { icon("AppIcon.icns")?.let { iconFile.set(it) } }
            windows { icon("AppIcon.ico")?.let { iconFile.set(it) } }
            linux { icon("AppIcon.png")?.let { iconFile.set(it) } }
        }
    }
}

tasks.processResources {
    from(iconsDir) { include("AppIcon.png") }
}

val compileIconFunction = """
    compile_icon() {
        name="$(basename "$1" .icon)"
        out="$(mktemp -d)"
        xcrun actool "$1" --compile "${'$'}out" --app-icon "${'$'}name" --platform macosx --target-device mac \
            --minimum-deployment-target 11.0 --output-partial-info-plist "${'$'}out/partial.plist" >/dev/null
        cp "${'$'}out/Assets.car" "$2/Contents/Resources/Assets.car"
        rm -rf "${'$'}out"
        /usr/libexec/PlistBuddy -c "Delete :CFBundleIconName" "$2/Contents/Info.plist" 2>/dev/null || true
        /usr/libexec/PlistBuddy -c "Add :CFBundleIconName string ${'$'}name" "$2/Contents/Info.plist"
    }
""".trimIndent()

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
        val iconComposerFile: Property<String>
        val compileIconFunction: Property<String>
    }

    @get:Inject abstract val exec: ExecOperations

    override fun obtain(): String {
        val p = parameters
        val bundle = File(p.bundleDir.get())
        val contents = bundle.resolve("Contents")
        val executableName = p.appName.get().filter { it.isLetterOrDigit() }
        val executable = contents.resolve("MacOS/$executableName")
        val iconFile = p.iconFile.orNull?.let(::File)
        val iconComposerFile = p.iconComposerFile.orNull?.let(::File)?.takeIf { it.isDirectory }
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
            iconComposerFile?.let { icon -> "${icon.absolutePath}@${icon.walk().maxOf { it.lastModified() }}" },
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
        if (iconComposerFile != null) {
            exec.exec {
                commandLine(
                    "/bin/sh", "-c", "set -eu\n${p.compileIconFunction.get()}\ncompile_icon \"$1\" \"$2\"",
                    "sh", iconComposerFile.absolutePath, bundle.absolutePath,
                )
            }
        }
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
    val devAppExecutable = providers.of(LiquidGlassDevApp::class) {
        // Resolved to a plain string: the configuration cache can't restore a toolchain-backed provider here.
        parameters.javaHome = jbrLauncher.get().metadata.installationPath.asFile.absolutePath
        parameters.bundleDir = layout.buildDirectory.dir("liquid-glass/Mauve Launcher.app").map { it.asFile.absolutePath }
        parameters.appName = "Mauve Launcher"
        parameters.bundleId = "dev.pencilstub.mauvelauncher.dev"
        parameters.iconFile = compose.desktop.application.nativeDistributions.macOS.iconFile.map { it.asFile.absolutePath }
        parameters.iconComposerFile = iconComposerFile?.absolutePath
        parameters.compileIconFunction = compileIconFunction
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

    tasks.matching { it.name == "createDistributable" }.configureEach {
        val appsDir = layout.buildDirectory.dir("compose/binaries/main/app").get().asFile
        val iconFile = iconComposerFile
        val script = "set -eu\n$stampSdk26\n$compileIconFunction\n" +
            "if [ -n \"$3\" ]; then compile_icon \"$3\" \"$2\"; fi\n" +
            "codesign --force --deep --sign - \"$2\""
        doLast {
            appsDir.listFiles { f -> f.extension == "app" }!!.forEach { app ->
                val launcher = app.resolve("Contents/MacOS").listFiles()!!.single { it.isFile && it.canExecute() }
                val proc = ProcessBuilder("/bin/sh", "-c", script, "sh", launcher.absolutePath, app.absolutePath, iconFile?.absolutePath.orEmpty())
                    .inheritIO().start()
                check(proc.waitFor() == 0) { "Failed to stamp, add the icon to, or re-sign ${app.name}" }
            }
        }
    }
}