plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        // Android 10 (API 29): ROLE_ASSISTANT, microphone foreground-service type, scoped storage (MediaStore Documents/JARVIS
        // without a storage permission), DisplayCutout, dark theme. Covers ~97% of active devices.
        minSdk = 29
        // Android 16 (API 36): current Google Play target requirement. Edge-to-edge and predictive back are handled in BaseActivity.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    // Optional local wake-word engine (openWakeWord models run on ONNX Runtime). Only loaded when the user downloads the models.
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
