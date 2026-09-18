import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Homey OAuth2 credentials have been moved exclusively to the Companion App.
// No sensitive credentials are required directly in the AAOS app build.

android {
    namespace = "com.dimapp.android.homeyautomotive"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dimapp.android.homeyautomotive"
        minSdk = 29
        targetSdk = 36
        versionCode = 41
        versionName = "1.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"



    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Car App Library (AAOS — categoria IOT)
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.automotive)

    // HTTP client — Retrofit + OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.coil)
    implementation(libs.coil.svg)

    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Lifecycle / ViewModel (per coroutine scope nei Screen)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Storage cifrato per il Personal Access Token
    implementation(libs.androidx.security.crypto)

    // Location & Geofencing
    implementation(libs.play.services.location)
    implementation(libs.androidx.work.runtime.ktx)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}