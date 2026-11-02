plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM module: no Android classes. Everything that decides what JARVIS does
// (routing, agent loop, tools, time parsing, LLM gateway, safety, geometry) lives here so it
// can be unit-tested on a plain JVM. The Android app module supplies the platform adapters.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
