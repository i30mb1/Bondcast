plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlinGradlePlugin)
    implementation(libs.kspGradlePlugin)
    implementation(libs.koverGradlePlugin)
    implementation(libs.kotlinSerializationPlugin)
    implementation(projects.buildExtensions)
}

gradlePlugin {
    plugins {
        register("KotlinKaptPlugin") {
            id = "n7.plugins.kotlin-kapt"
            implementationClass = "n7.plugins.KotlinKaptPlugin"
            displayName = "Kotlin Kapt Plugin"
        }
        register("KotlinKspPlugin") {
            id = "n7.plugins.kotlin-ksp"
            implementationClass = "n7.plugins.KotlinKspPlugin"
            displayName = "Kotlin KSP Plugin"
        }
    }
}
