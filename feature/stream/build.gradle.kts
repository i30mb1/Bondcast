plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "n7.bondcast.feature.stream"
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
    implementation(project(":feature:bonding"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:thermal"))
    implementation(project(":feature:camera"))
    implementation(project(":core:ui"))
    implementation(project(":kotlin"))

    implementation(libs.streampack.core)
    implementation(libs.streampack.srt)
    implementation(libs.streampack.ui)
    implementation(libs.srtdroid.core)

    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
}
