// Glue: chatgpt-auth -> coordinator Llm, text-agents -> coordinator Peer / event pump, text-agents -> inbox,
// relay-client -> coordinator Peer (ConnectorAgent) / event pump (C2).
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":coordinator"))
    api(project(":inbox"))
    api(project(":text-agents:core"))
    api(project(":chatgpt-auth"))
    // C2: Juno's asks over the Atall connector (signed task cards, countersigned events).
    api(project(":relay-client"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
