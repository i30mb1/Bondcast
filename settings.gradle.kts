pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        maven(url = "https://repo.maven.apache.org/maven2/")
        google()
    }
}

include("kotlin")
include("android")
