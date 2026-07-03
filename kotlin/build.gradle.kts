plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
}

kotlin {
    explicitApi()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.1.21")
    testImplementation("junit:junit:4.13.2")
}