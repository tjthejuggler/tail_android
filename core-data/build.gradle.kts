plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.tail.coredata"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    testOptions {
        // Repository classes call android.util.Log; return default values
        // instead of throwing "not mocked" in JVM unit tests.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(libs.androidx.core.ktx)
    api(libs.gson)
    api(libs.kotlinx.coroutines.android)
    api(libs.androidx.datastore.preferences)
    api(libs.androidx.documentfile)
    api(libs.androidx.work.runtime.ktx)
    api(libs.play.services.auth)
}
