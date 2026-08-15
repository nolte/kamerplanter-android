import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.github.nolte.kamerplanter.feature.pestdetection"
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
    // The detection seam and its app-owned types. No Retrofit type, no generated DTO and no
    // OkHttp type crosses into this module (ADR 0001, R-GEN-5).
    //
    // `api`, not `implementation`: PestDetectionState carries Detection and Finding, so both
    // are part of this module's own surface and have to travel with the dependency.
    api(project(":core:network"))

    // For `MicroscopeCamera` and `CapturedFrame` only — the app-owned seam in front of the UVC
    // engine. The engine itself (libuvc) stays inside :feature:microscope, which is what the
    // isolation rule in ADR 0001 asks for: this module consumes frames through the interface
    // and never names the library.
    implementation(project(":feature:microscope"))

    // The device camera — the second image source (#10) — and the CAMERA runtime permission
    // both screens need.
    implementation(project(":core:camera"))

    // Declared rather than inherited: both are used directly here — the permission launcher
    // and ContextCompat — and they reach this module only through hilt-navigation-compose's
    // own api dependencies today. A version bump that dropped either would break this module
    // for a reason nothing in it names.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
