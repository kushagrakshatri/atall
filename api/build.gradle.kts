// Seams between the app and modules built separately: the coordinator (app.atall.coordinator),
// the agent inbox (app.atall.inbox) and non-RCS channel adapters (app-agents). Pure Kotlin/JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":text-agents:core"))
    api(project(":chatgpt-auth"))
}
