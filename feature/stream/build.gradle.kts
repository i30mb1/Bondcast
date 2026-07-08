plugins {
    id("convention.android-library")
    id("convention.compose")
}

dependencies {
    implementation(project(":feature:bonding:impl"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:thermal"))
    implementation(project(":feature:obs"))
    implementation(project(":feature:camera"))
    api(project(":feature:overlay"))
    implementation(project(":core:ui"))
    implementation(project(":feature:bonding:domain"))

    implementation(libs.streampack.core)
    implementation(libs.streampack.srt)
    implementation(libs.srtdroid.core)
    implementation(libs.srtdroid.ktx)

    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.camera.compose)

    implementation(libs.activity.compose)
}
