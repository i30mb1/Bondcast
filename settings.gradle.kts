pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        maven(url = "https://repo.maven.apache.org/maven2/")
        google()
    }
}

include("kotlin")
include("android")
