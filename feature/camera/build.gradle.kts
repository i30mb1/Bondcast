plugins {
    id("convention.android-library")
    id("convention.compose")
}

dependencies {
    implementation(project(":feature:camera:libuvccamera"))
    implementation(project(":core:ui"))
    api(project(":feature:overlay"))

    api(libs.streampack.core)
    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.effects)
}
