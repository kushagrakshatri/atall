// Pure JVM: the Agent Inbox (dedupe, triage, redaction, digest, store). No Android dependency, so `:inbox:test`
// runs on any JVM. Not wired into the thread yet (the app shows every unsolicited message); see README.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin { jvmToolchain(17) }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}
