plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "n7.bondcast.feature.overlay"
    compileSdk = 37

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
    implementation(libs.core.ktx)
}
