import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

group = "io.github.ezoushen"
version = findProperty("VERSION_NAME")?.toString() ?: "0.1.0"

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        publishLibraryVariants("release")
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
            implementation(project(":blur-core"))
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

mavenPublishing {
    configure(KotlinMultiplatform(javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty()))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    pom {
        name.set("blur-cmp")
        description.set("Compose Multiplatform real-time blur overlay for Android and iOS")
        url.set("https://github.com/ezoushen/blur-cmp")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }

        developers {
            developer {
                id.set("ezoushen")
                name.set("ezoushen")
                url.set("https://github.com/ezoushen")
            }
        }

        scm {
            connection.set("scm:git:github.com/ezoushen/blur-cmp.git")
            developerConnection.set("scm:git:ssh://github.com/ezoushen/blur-cmp.git")
            url.set("https://github.com/ezoushen/blur-cmp/tree/main")
        }
    }
}
