plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// GitHub Actions sets GITHUB_RUN_NUMBER, so every build gets a higher version number.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "nl.jdatje.framily"
    compileSdk = 35

    defaultConfig {
        applicationId = "nl.jdatje.framily"
        minSdk = 33
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.7.$buildNumber"

        // Only the processor type modern phones (like the Pixel 9) use; keeps the app much smaller.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // A fixed signing key, so a new build installs over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            // Remove unused library code (makes the APK a lot smaller).
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    // Store native libraries compressed: a much smaller download.
    packaging {
        jniLibs.useLegacyPackaging = true
    }
    // The face-recognition model is read straight from the APK, so it must not be compressed.
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // On-device ML: everything runs on the phone, no photo is ever uploaded.
    // Face detection and the face-recognition runtime come from Google Play services (keeps the app small)
    implementation("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")
    implementation("com.google.android.gms:play-services-tflite-java:16.4.0")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
