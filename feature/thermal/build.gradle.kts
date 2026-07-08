plugins {
    id("convention.android-library")
    id("convention.compose")
}

dependencies {
    implementation(project(":core:ui"))

    implementation(libs.kotlinx.coroutines.android)
}
