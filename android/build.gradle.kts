plugins {
    id("com.android.application") version "8.11.0"
    id("org.jetbrains.kotlin.android") version "2.1.20"
}

android {
    namespace = "n7.test"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        targetSdk = 35
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}