import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "io.github.ezoushen"

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
            baseName = "BlurCmp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
        }

        androidMain.dependencies {
            implementation("io.github.ezoushen:blur-core")
            implementation(libs.androidx.annotation)
        }

        iosMain.dependencies {
            // iOS blur views bundled as Swift sources
        }
    }
}

android {
    namespace = "io.github.ezoushen.blur.cmp"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

afterEvaluate {
    publishing {
        publications.withType<MavenPublication> {
            groupId = findProperty("GROUP")?.toString() ?: "io.github.ezoushen"
            version = findProperty("VERSION_NAME")?.toString() ?: "1.0.0"

            pom {
                name.set("blur-cmp")
                description.set("Compose Multiplatform real-time blur overlay for Android and iOS")
                url.set("https://github.com/ezoushen/blur-android")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }

        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/ezoushen/blur-android")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                        ?: findProperty("gpr.user")?.toString()
                    password = System.getenv("GITHUB_TOKEN")
                        ?: findProperty("gpr.token")?.toString()
                }
            }
        }
    }
}
