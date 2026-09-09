plugins {
    id("convention.android-library")
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.test.kotlin.junit)
    testImplementation(libs.test.junit)
}
