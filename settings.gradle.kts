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
        maven(url = "https://jitpack.io")
    }
}

include("kotlin")
include("android")
include("libuvccamera")
include("benchmark")
include(":core:ui")
include(":feature:bonding")
include(":feature:settings")
include(":feature:thermal")
include(":feature:camera")
include(":feature:stream")
