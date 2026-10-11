plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.metasousa.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.metasousa.app"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "2.0.0"

        // Modo desarrollador: sin atestación, no hace falta token
        manifestPlaceholders["mwdat_application_id"] = "0"
        manifestPlaceholders["mwdat_client_token"] = "0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Meta Wearables Device Access Toolkit (desde Maven Central, sin token)
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    // WebSocket para Gemini Live
    implementation(libs.okhttp)
}
