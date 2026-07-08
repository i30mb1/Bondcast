enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Bondcast"

pluginManagement {
    includeBuild("build-logic/build-settings")
}

plugins {
    id("n7.plugins.settings")
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
