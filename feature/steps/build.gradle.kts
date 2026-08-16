plugins {
    id("convention.android-library")
    id("convention.compose")
}

dependencies {
    api(project(":feature:overlay"))
    implementation(project(":core:ui"))

    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.activity.compose)

    testImplementation(libs.test.junit)
    testImplementation(libs.test.kotlin.junit)
}
