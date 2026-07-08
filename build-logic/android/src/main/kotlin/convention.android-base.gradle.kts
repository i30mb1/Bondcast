import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

pluginManager.withPlugin("com.android.application") {
    extensions.configure(ApplicationExtension::class.java) {
        compileSdk = 37
        defaultConfig {
            minSdk = 35
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        testOptions {
            unitTests {
                isIncludeAndroidResources = true
            }
        }
        lint {
            disable += setOf(
                "CoroutineCreationDuringComposition",
                "RestrictedApi",
                "UnknownNullness",
            )
            abortOnError = false
            warningsAsErrors = false
        }
    }
}

pluginManager.withPlugin("com.android.library") {
    extensions.configure(LibraryExtension::class.java) {
        compileSdk = 37
        defaultConfig {
            minSdk = 35
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        testOptions {
            unitTests {
                isIncludeAndroidResources = true
            }
        }
        lint {
            disable += setOf(
                "CoroutineCreationDuringComposition",
                "RestrictedApi",
                "UnknownNullness",
            )
            abortOnError = false
            warningsAsErrors = false
        }
    }
}

tasks.withType<Test> {
    testLogging.events("passed", "skipped", "failed", "standardOut", "standardError")
    systemProperty("junit.platform.output.capture.stdout", "true")
    systemProperty("junit.platform.output.capture.stderr", "true")
}
