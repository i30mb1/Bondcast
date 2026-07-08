dependencyResolutionManagement {
    repositories {
        google()
        maven { setUrl("https://repo.maven.apache.org/maven2/") }
        maven { setUrl("https://jitpack.io") }
    }
    versionCatalogs {
        create("libs") {
            from(files("build-logic/gradle/libs.versions.toml"))
        }
    }
}

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google() // com.android.library, com.android.tools.build:gradle
        gradlePluginPortal() // kotlin-dsl, kotlin, jvm, kapt
        maven { setUrl("https://repo.maven.apache.org/maven2/") }
        maven { setUrl("https://jitpack.io") }
    }
}
includeBuild("build-logic")
