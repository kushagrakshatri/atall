// The Android edge: NotificationListenerService, reply-action firing, draft intents. AGP 9 compiles Kotlin itself
// (built-in Kotlin); on AGP 8 add id("org.jetbrains.kotlin.android") here.
plugins {
    id("com.android.library")
}

android {
    namespace = "app.atall.agents.text.android"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":text-agents:core"))
}
