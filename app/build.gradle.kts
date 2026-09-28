plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.example.meshmessenger"; compileSdk = 36
    defaultConfig { applicationId = "com.example.meshmessenger"; minSdk = 26; targetSdk = 36; versionCode = 17; versionName = "0.17.0" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.zxing:core:3.5.3")
}

// QR contact cards
// ZXing is used only for local QR rendering; no network/service is required.
