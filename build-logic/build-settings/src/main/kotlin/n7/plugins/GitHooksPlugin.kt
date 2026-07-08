package n7.plugins

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

class GitHooksPlugin : Plugin<Settings> {

    override fun apply(target: Settings) = with(target) {
        try {
            val output = providers.exec {
                commandLine("git", "config", "core.hooksPath", ".githooks")
            }
            val result = when (output.result.orNull?.exitValue) {
                0 -> "success"
                else -> "failure"
            }

            println("Bondcast: Setup git hooks: $result")
        } catch (e: Exception) {
            println("Bondcast: Error when trying to set git-hooks")
        }
    }
}
