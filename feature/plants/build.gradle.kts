import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.github.nolte.kamerplanter.feature.plants"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        managedDevices {
            localDevices {
                // Same device the app module declares, for the same reason and with the same
                // caveat: a Pixel 7a *profile*, not the physical Pixel 7a, so nothing measured
                // here is device verification. Repeated per module because the managed-device
                // block is per-project; keep the two in step.
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
    // Whether an instance is connected — the list is gated on it and shows nothing without
    // one. This module reads the connection; it never establishes one.
    // Photo picking and the CAMERA grant for a diary entry's pictures. The same module the
    // pest-detection flow takes its phone camera from, so the permission and the file provider
    // it needs are declared once.
    implementation(project(":core:camera"))
    // The microscope, for its interface only — the UVC engine stays inside that module
    // (ADR 0001). :feature:pestdetection depends on it for the same reason.
    implementation(project(":feature:microscope"))
    implementation(project(":core:connection"))

    // For the PlantsClient seam and its app-owned types only. No Retrofit type, no generated
    // DTO and no OkHttp type crosses into this module (ADR 0001, R-GEN-5) — the one exception
    // is the OkHttpClient handed to Coil below, which carries the credential to thumbnail
    // requests and never appears in this module's own API.
    //
    // `api`, not `implementation`: PlantListState.Content carries List<PlantSummary> and the
    // ViewModel exposes AuthenticatedImageClient, so both are part of this module's own
    // surface and have to travel with the dependency.
    api(project(":core:network"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Thumbnails. The OkHttp-backed loader is what lets the stored credential reach an
    // attachment URI, which is tenant-scoped and authenticated.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    // The in-memory CredentialStore and ConnectionStore, so the page's image client can be
    // built without a real connection — same fixtures the unit tests use.
    androidTestImplementation(testFixtures(project(":core:connection")))
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The in-memory CredentialStore, so the ViewModel's image client can be built off-device.
    testImplementation(testFixtures(project(":core:connection")))
}
