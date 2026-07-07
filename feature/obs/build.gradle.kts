plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "n7.bondcast.feature.obs"
    compileSdk = 36

    defaultConfig {
        minSdk = 35
    }

    buildFeatures {
        compose = true
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":feature:settings"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.0")
    // в android.jar org.json — заглушки, для JVM-тестов нужна настоящая реализация
    testImplementation("org.json:json:20250107")
}
