plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.abl.calls"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.abl.calls"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Production API; can be changed on the sign-in screen (e.g. http://192.168.1.10:8000/api for dev).
        buildConfigField("String", "DEFAULT_BASE_URL", "\"https://api.ableducation.com/api\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            manifestPlaceholders["cleartext"] = "false"
        }
        debug {
            manifestPlaceholders["cleartext"] = "true"
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
