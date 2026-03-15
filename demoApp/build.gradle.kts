import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "DemoApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(project(":blur-cmp"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}

// ── iOS Simulator run task ─────────────────────────────────────────────
// Usage: ./gradlew :demoApp:runIosSimulator
// Builds the framework, compiles the Swift app via xcodebuild, installs
// on the booted simulator, and launches.

tasks.register("runIosSimulator") {
    group = "run"
    description = "Build and run the iOS demo app on the booted simulator"
    dependsOn("linkDebugFrameworkIosSimulatorArm64")

    doLast {
        val iosAppDir = rootProject.file("iosApp")
        val xcodeproj = File(iosAppDir, "iosApp.xcodeproj")

        // Find a booted simulator
        val simListResult = providers.exec {
            commandLine("xcrun", "simctl", "list", "devices", "booted", "-j")
        }.standardOutput.asText.get()

        // Parse booted device UDID from JSON (simple regex — avoids JSON lib dependency)
        val udidPattern = """"udid"\s*:\s*"([^"]+)"""".toRegex()
        val statePattern = """"state"\s*:\s*"Booted"""".toRegex()
        var bootedUdid: String? = null
        val lines = simListResult.lines()
        for (i in lines.indices) {
            if (statePattern.containsMatchIn(lines[i])) {
                // Search nearby lines for udid
                for (j in maxOf(0, i - 5)..minOf(lines.size - 1, i + 5)) {
                    udidPattern.find(lines[j])?.let { bootedUdid = it.groupValues[1] }
                    if (bootedUdid != null) break
                }
            }
            if (bootedUdid != null) break
        }

        if (bootedUdid == null) {
            // Boot the default iPhone simulator
            val defaultSim = "iPhone 17 Pro"
            exec { commandLine("xcrun", "simctl", "boot", defaultSim) }
            // Re-query
            val requery = providers.exec {
                commandLine("xcrun", "simctl", "list", "devices", "booted", "-j")
            }.standardOutput.asText.get()
            for (i in requery.lines().indices) {
                if (statePattern.containsMatchIn(requery.lines()[i])) {
                    for (j in maxOf(0, i - 5)..minOf(requery.lines().size - 1, i + 5)) {
                        udidPattern.find(requery.lines()[j])?.let { bootedUdid = it.groupValues[1] }
                        if (bootedUdid != null) break
                    }
                }
                if (bootedUdid != null) break
            }
        }

        requireNotNull(bootedUdid) { "No booted simulator found and could not boot one." }
        logger.lifecycle("Using simulator: $bootedUdid")

        // Build via xcodebuild
        exec {
            workingDir = iosAppDir
            commandLine(
                "xcodebuild",
                "-project", xcodeproj.absolutePath,
                "-scheme", "iosApp",
                "-destination", "id=$bootedUdid",
                "-configuration", "Debug",
                "build",
            )
        }

        // Find the built .app
        val derivedData = File(System.getProperty("user.home"), "Library/Developer/Xcode/DerivedData")
        val appBundle = derivedData.walkTopDown()
            .filter { it.name == "iosApp.app" && it.parentFile.name == "Debug-iphonesimulator" }
            .sortedByDescending { it.lastModified() }
            .firstOrNull() ?: error("Could not find built iosApp.app")

        logger.lifecycle("Installing: ${appBundle.absolutePath}")

        // Install and launch
        exec { commandLine("xcrun", "simctl", "install", bootedUdid, appBundle.absolutePath) }
        exec { commandLine("xcrun", "simctl", "launch", bootedUdid, "io.github.ezoushen.blur.demo") }
        exec { commandLine("open", "-a", "Simulator") }

        logger.lifecycle("App launched on simulator $bootedUdid")
    }
}

// ── Android run task ──────────────────────────────────────────────────
// Usage: ./gradlew :demoApp:runAndroid
// Builds and installs the debug APK, then launches the main activity.

tasks.register("runAndroid") {
    group = "run"
    description = "Build, install, and launch the Android demo app on a connected device"
    dependsOn("installDebug")

    doLast {
        val sdkDir = android.sdkDirectory
        val adb = File(sdkDir, "platform-tools/adb")
        exec {
            commandLine(
                adb.absolutePath, "shell", "am", "start",
                "-n", "io.github.ezoushen.blur.demo/.MainActivity",
            )
        }
        logger.lifecycle("App launched on Android device")
    }
}

android {
    namespace = "io.github.ezoushen.blur.demo"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.ezoushen.blur.demo"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
