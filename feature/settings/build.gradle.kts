plugins {
    id("convention.android-library")
    id("convention.compose")
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":feature:qr"))

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.activity.compose)
}
