plugins {
    id("convention.android-library")
    id("convention.compose")
}

dependencies {
    implementation(project(":core:ui"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.activity.compose)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.compose)
    implementation(libs.mlkit.barcode.scanning)

    testImplementation(libs.test.junit)
    testImplementation(libs.test.kotlin.junit)
}
