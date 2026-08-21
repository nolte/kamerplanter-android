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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        managedDevices {
            localDevices {
                // Same device and same caveat as the other modules: a Pixel 7a *profile*, not
                // the physical Pixel 7a. Repeated because the block is per-project.
                create("pixel7aApi36") {
                    device = "Pixel 7a"
                    apiLevel = 36
                    systemImageSource = "aosp-atd"
                }
            }
        }
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

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
