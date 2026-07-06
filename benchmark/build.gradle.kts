import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
    id("org.jetbrains.kotlin.kapt") version "2.1.21"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":kotlin"))
    implementation("org.openjdk.jmh:jmh-core:1.37")
    kapt("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Запуск JMH-микробенчмарков горячего пути srtla"
    mainClass.set("org.openjdk.jmh.Main")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
    val extra = (project.findProperty("jmhArgs") as String?)
        ?.split(" ")
        ?.filter { it.isNotBlank() }
    args = extra ?: listOf("-prof", "gc")
}
