plugins {
    id("convention.kotlin-jvm")
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.test.kotlin.junit)
    testImplementation(libs.test.junit)
}
