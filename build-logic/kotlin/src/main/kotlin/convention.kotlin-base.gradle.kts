import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("extensions")
}

configure<JavaPluginExtension> {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.DelicateCoroutinesApi",
            "-Xskip-metadata-version-check",
        )
        if (System.getProperty("idea.active") == "true") {
            freeCompilerArgs.add("-Xdebug")
        }
    }
}
