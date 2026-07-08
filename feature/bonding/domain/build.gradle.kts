plugins {
    id("convention.kotlin-jvm")
}

kotlin {
    explicitApi()
}

dependencies {
    testImplementation(libs.test.kotlin.junit)
    testImplementation(libs.test.junit)
}
