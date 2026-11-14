// Glue: chatgpt-auth -> coordinator Llm, text-agents -> coordinator Peer / event pump, text-agents -> inbox.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":coordinator"))
    api(project(":inbox"))
    api(project(":text-agents:core"))
    api(project(":chatgpt-auth"))
}
