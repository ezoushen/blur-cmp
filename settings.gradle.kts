pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// Reference blur-android for blur-core dependency
includeBuild("../blur-android") {
    dependencySubstitution {
        substitute(module("io.github.ezoushen:blur-core")).using(project(":blur-core"))
    }
}

rootProject.name = "blur-cmp"
include(":blur-cmp")
include(":demoApp")
