// The Android edge of the relay client: the non-exportable P-256 device key in Android Keystore and the
// SharedPreferences store for device_id, the relay key and the events cursor.
plugins {
    id("com.android.library")
}

android {
    namespace = "app.atall.relay.android"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":relay-client"))
}
