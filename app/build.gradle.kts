plugins {
    id("convention.android-application")
    id("convention.compose")
}

android {
    namespace = "n7.bondcast"

    defaultConfig {
        targetSdk = 36
        versionCode = getVersionCode()
        versionName = getVersionName()
    }

    buildTypes {
        release {
            isDebuggable = false
            isShrinkResources = true
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":feature:bonding:impl"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:thermal"))
    implementation(project(":feature:obs"))
    implementation(project(":feature:overlay"))
    implementation(project(":feature:stream"))
    implementation(project(":feature:chat:twitch"))
    implementation(project(":feature:chat:impl"))

    implementation(libs.activity.compose)
    implementation(libs.material)

    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
