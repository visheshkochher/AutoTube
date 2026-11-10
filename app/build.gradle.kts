plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vk.autotube"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vk.autotube"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    // Fixed key, so every new build installs over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("autotube-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
