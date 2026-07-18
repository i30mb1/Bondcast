@file:Suppress("UnstableApiUsage")

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

pluginManager.withPlugin("com.android.application") {
    extensions.configure(ApplicationExtension::class.java) {
        buildFeatures {
            compose = true
        }
    }
}

pluginManager.withPlugin("com.android.library") {
    extensions.configure(LibraryExtension::class.java) {
        buildFeatures {
            compose = true
        }
    }
}

configure<ComposeCompilerGradlePluginExtension> {
    includeSourceInformation = true
    val file = layout.projectDirectory.file("stability_config.conf")
    if (file.asFile.exists()) {
        stabilityConfigurationFile.set(file)
    }

    // Generate file with info about compose functions
    // use Gradle task *:compile****Kotlin --rerun-tasks
    metricsDestination.set(layout.buildDirectory.dir("compose_metrics"))
    reportsDestination.set(layout.buildDirectory.dir("compose_metrics"))
}

dependencies {
    add("implementation", platform(catalog.findLibrary("compose-bom").get()))
    add("implementation", catalog.findBundle("compose").get())
}
