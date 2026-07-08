plugins {
    id("convention.android-library")
    id("convention.compose")
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":feature:settings"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.test.junit)
    testImplementation(libs.test.kotlin.junit)
    // в android.jar org.json — заглушки, для JVM-тестов нужна настоящая реализация
    testImplementation(libs.test.json)
}
