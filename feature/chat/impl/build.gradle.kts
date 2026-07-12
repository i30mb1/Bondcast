plugins {
    id("convention.android-library")
    id("convention.compose")
}

dependencies {
    implementation(project(":core:ui"))
    // домен — часть публичного API (ChatController.messages: StateFlow<ChatEvent.Message>)
    api(project(":feature:chat:domain"))
    implementation(project(":feature:settings"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
