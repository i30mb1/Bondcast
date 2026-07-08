import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("convention.kotlin-jvm")
    id("n7.plugins.kotlin-kapt")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":feature:bonding:domain"))
    implementation(libs.jmh.core)
    kapt(libs.jmh.generator)
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
