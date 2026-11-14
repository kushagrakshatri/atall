pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Atall"
include(":app")
// Seams other modules plug into (coordinator, inbox, channel adapters). Pure Kotlin.
include(":api")
// Texting-agent transport (Google Messages notifications + reply actions).
include(":text-agents:core", ":text-agents:android")
// Sign in with ChatGPT + Responses client. Pure Kotlin.
include(":chatgpt-auth")
