plugins {
    id("convention.android-library")
}

dependencies {
    // домен — часть публичного API (TwitchChat.source: ChatSource)
    api(project(":feature:chat:domain"))

    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.test.junit)
    testImplementation(libs.test.kotlin.junit)
    // в android.jar org.json — заглушки, для JVM-тестов нужна настоящая реализация
    testImplementation(libs.test.json)
}
