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
// Juno: routing, the ask/wait/merge tool loop and the @all Agreement Map. Pure Kotlin; glue in :coordinator:adapters.
include(":coordinator", ":coordinator:adapters")
// Agent Inbox (dedupe, triage, digest). Pure Kotlin; built and tested, not yet wired into the thread.
include(":inbox")
