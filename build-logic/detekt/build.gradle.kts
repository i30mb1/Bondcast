plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.spotlessPlugin)
    implementation(libs.detektPlugin)
    // только API-артефакт (KotlinBasePlugin) — без KotlinAndroidTarget, который тянет удалённый в AGP 9 BaseVariant
    implementation(libs.kotlinGradlePluginApi)

    implementation(projects.buildExtensions)
}
