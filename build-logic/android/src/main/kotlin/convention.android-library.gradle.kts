plugins {
    id("com.android.library")
    id("convention.android-base")
    id("convention.kotlin-base")
}

android {
    val projectNameFormatted = project.path.drop(1).replace(Regex("[-:]"), ".")
    namespace = "$applicationID.$projectNameFormatted"
    packaging {
        resources.excludes.add("META-INF/*")
    }
    buildTypes {
        getByName("debug") {
            matchingFallbacks.add("release")
        }
    }
}
