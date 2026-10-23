plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qui.popupdict"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qui.popupdict"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation("androidx.core:core-ktx:1.12.0")
    // OCR tiếng Latin (ML Kit), model tải qua Google Play Services
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")
}
