plugins {
    id("convention.android-library")
}

dependencies {
    implementation(libs.core.ktx)

    testImplementation(libs.test.junit)
    testImplementation(libs.test.kotlin.junit)
}
