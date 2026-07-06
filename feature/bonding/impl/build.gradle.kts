plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "n7.bondcast.feature.bonding"
    compileSdk = 36

    defaultConfig {
        minSdk = 35
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation(project(":feature:bonding:domain"))
    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
