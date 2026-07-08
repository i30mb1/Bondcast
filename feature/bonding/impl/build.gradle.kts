plugins {
    id("convention.android-library")
}

dependencies {
    implementation(project(":feature:bonding:domain"))
    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
