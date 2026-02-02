// app/build.gradle.kts (Top of file)
import java.io.FileInputStream
import java.util.Properties

val properties = Properties()
val localProperties = rootProject.file("local.properties")
if (localProperties.exists()) {
    properties.load(FileInputStream(localProperties))
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("kotlin-kapt")

}

android {
    namespace = "com.farmerassistant.app"
    compileSdk = 34
    signingConfigs {
        // 1. Define the NEW DEBUG CONFIGURATION explicitly
        create("framerDebug") {
            storeFile = file("../debug_framer.jks")
            storePassword = "android"
            keyAlias = "framerDebugKey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // 2. Assign the 'framerDebug' configuration to the 'debug' build type
        getByName("debug") {
            signingConfig = signingConfigs.getByName("framerDebug")
            // Ensure this debug block does NOT contain: signingConfig = signingConfigs.getByName("debug")
        }

        // Ensure you have a release block, using the default debug key for unsigned release builds
        getByName("release") {
            isMinifyEnabled = false // Keep false for now
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Use the standard debug key as a placeholder if you don't have a release key
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    defaultConfig {
        applicationId = "com.farmerassistant.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk {
            // Include only the architectures needed for faster deployment and TFLite compatibility
            // arm64-v8a is the modern 64-bit architecture
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
        // FIX: Use safe accessor for property or default to string, cast is not needed with get()
        buildConfigField("String", "GEMINI_API_KEY", "\"${properties["GEMINI_API_KEY"] ?: "MISSING_KEY"}\"")
    }


    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // AndroidX & UI
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Updated Material dependency for Material 3 support
    implementation("com.google.android.material:material:1.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Firebase Core, Auth, Firestore, Functions
    implementation(platform("com.google.firebase:firebase-bom:33.0.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.android.gms:play-services-auth:21.0.0") // Google Sign-In

    // Maps: OpenStreetMap (OSMDroid) & Location Services
    implementation("org.osmdroid:osmdroid-android:6.1.18") // OSMDroid
    implementation("com.google.android.gms:play-services-location:21.0.1") // Standard Android GPS

    // Networking/ML (Phase 3 & 4)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    val tfliteVersion = "2.16.1"
    implementation("org.tensorflow:tensorflow-lite:$tfliteVersion")

    // Recommended: Add the GPU delegate dependency for advanced optimization support
    // (This helps ensure all runtime components are modern and compatible)
    implementation("org.tensorflow:tensorflow-lite-gpu:$tfliteVersion")
    implementation("com.google.firebase:firebase-storage-ktx")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // NEW DEPENDENCIES FOR PHASE 8:
    // WorkManager (for background soil health task)
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    // Firebase Cloud Messaging (for push notifications)
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.maps.android:android-maps-utils:2.4.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")
    implementation("jp.wasabeef:glide-transformations:4.3.0")
// Check for latest version
}
