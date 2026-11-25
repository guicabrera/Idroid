plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.idroid.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.idroid.app"
        // 29 (Android 10) matches the clipboard-access baseline already assumed by
        // ADR-0002 and lets downloads use the modern MediaStore.Downloads API.
        minSdk = 29
        targetSdk = 35
        versionCode = 16
        versionName = "0.8.4"
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

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Adaptive icon XML resolution relies on the "-v26" qualifier on the
        // mipmap-anydpi folder regardless of minSdk; lint's suggestion to
        // drop it (since minSdk already exceeds 26) breaks resource linking.
        disable += "ObsoleteSdkInt"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // Google's ready-made QR scanner UI: no CAMERA permission, no camera code here.
    implementation(libs.play.services.code.scanner)
    // The scanner pulls in a pre-1.3 Fragment, which breaks the ActivityResult APIs.
    implementation(libs.androidx.fragment)
    // SSH terminal (ADR-0005): Termux's VT emulator and renderer, JSch for the connection.
    implementation(libs.termux.terminal.emulator)
    implementation(libs.termux.terminal.view)
    implementation(libs.jsch)
}
