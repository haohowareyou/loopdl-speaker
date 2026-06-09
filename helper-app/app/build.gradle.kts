plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    namespace = "co.loop.speaker"
    compileSdk = 35
    defaultConfig {
        applicationId = "co.loop.speaker"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
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
}
