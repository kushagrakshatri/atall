import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from a properties file kept OUTSIDE the repo.
// Path is given by -PreleaseSigningProps=... or env RELEASE_SIGNING_PROPS.
// File keys: storeFile, storePassword, keyAlias, keyPassword.
val signingPropsPath: String? =
    (findProperty("releaseSigningProps") as String?) ?: System.getenv("RELEASE_SIGNING_PROPS")
val signingProps: Properties? = signingPropsPath?.let { p ->
    val f = file(p)
    if (f.isFile) Properties().apply { f.inputStream().use { load(it) } } else null
}

// Sentry DSN, never committed (the repo is public). In order: -PsentryDsn, env SENTRY_DSN (the CI secret), the `dsn`
// key of a properties file (-PsentryProps / env SENTRY_PROPS, else ../../.secrets/sentry.properties beside the repo).
// Empty means no build-time DSN: Sentry stays off unless one is entered in Settings (hidden debug field).
val sentryDsn: String = run {
    (findProperty("sentryDsn") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("SENTRY_DSN")?.takeIf { it.isNotBlank() }
        ?: listOfNotNull((findProperty("sentryProps") as String?) ?: System.getenv("SENTRY_PROPS"), rootProject.file("../../.secrets/sentry.properties").path)
            .map { file(it) }.firstOrNull { it.isFile }
            ?.let { f -> Properties().apply { f.inputStream().use { load(it) } }.getProperty("dsn")?.trim() }
        ?: ""
}

android {
    namespace = "app.atall"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.atall"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.4-m04"
        buildConfigField("String", "SENTRY_DSN", "\"" + sentryDsn.replace("\\", "").replace("\"", "") + "\"")
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*", "DebugProbesKt.bin")
    }
}

dependencies {
    implementation(project(":api"))
    implementation(project(":text-agents:android"))
    implementation(project(":chatgpt-auth"))
    // Juno (coordinator) + its glue to text-agents/chatgpt-auth, and the inbox module.
    implementation(project(":coordinator:adapters"))
    // Atall relay (C1): Keystore-signed phone requests and verified agent events.
    implementation(project(":relay-client:android"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Crashes, ANRs and run steps (off without a DSN). Core only: no NDK, no session replay.
    implementation(libs.sentry.android.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
