import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.github.nolte.kamerplanter.core.network"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // The OpenAPI-generated kamerplanter client (ADR 0001) lands in this module;
    // Retrofit/OkHttp/serialization are its runtime and part of this module's API.
    api(libs.retrofit)
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.converter.kotlinx.serialization)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}
