plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.photos"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.photos"
        minSdk = 35
        targetSdk = 35
        versionCode = 8
        versionName = "1.5.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    signingConfigs {
        if (!System.getenv("PHOTOS_KEYSTORE").isNullOrBlank()) {
            create("release") {
                storeFile = file(System.getenv("PHOTOS_KEYSTORE"))
                storePassword = requireNotNull(System.getenv("PHOTOS_STORE_PASSWORD"))
                keyAlias = requireNotNull(System.getenv("PHOTOS_KEY_ALIAS"))
                keyPassword = requireNotNull(System.getenv("PHOTOS_KEY_PASSWORD"))
            }
        }
    }
    buildTypes {
        release {
            // R8 shrinks and optimizes the release build (smaller APK, faster startup).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    testImplementation("junit:junit:4.13.2")
}
