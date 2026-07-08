import dev.detekt.gradle.Detekt

plugins {
    id("dev.detekt")
}

tasks.register<Detekt>("detektAll") {
    description = "Runs over whole code base without the starting overhead for each module."
    group = "n7"

    setSource(files(projectDir))

    config.setFrom(getResource("config.yml"))
    buildUponDefaultConfig = true

    include("**/*.kt")
    include("**/*.kts")
    exclude("**/resources/**")
    exclude("**/build/**")
    exclude("**/androidTest/**")

    reports {
        sarif.required.set(false)
    }
}
