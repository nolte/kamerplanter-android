import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.github.nolte.kamerplanter.feature.settings"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
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
    // Connection model and its two stores. `api`, not `implementation`: ConnectionState —
    // and therefore SettingsViewModel.state — exposes Connection to whoever renders this
    // screen, so the type has to travel with the dependency.
    api(project(":core:connection"))

    // For the ConnectionClient binding only — this module talks to the instance through
    // that seam and touches no networking type itself (ADR 0001, R-GEN-5).
    implementation(project(":core:network"))

    // The device camera and its runtime permission, shared with pest detection. ML Kit stays
    // here: barcode decoding is this feature's concern, holding a camera is not.
    implementation(project(":core:camera"))

    // The device-camera QR scanner lives here — and only here. The rest of the app
    // pairs through the app-owned PairingClient seam, never through CameraX/ML Kit.
    // (This is the phone camera, distinct from the USB/UVC camera in :feature:microscope.)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    implementation(platform(libs.compose.bom))
    // The pairing QR payload is a versioned JSON object, read by hand rather than
    // deserialised: an unknown version has to be refused, not mapped onto a data class.
    implementation(libs.kotlinx.serialization.json)

    // ContextCompat, used directly by the QR scanner's executor.
    // The pairing QR payload is a versioned JSON object, read by hand rather than
    // deserialised: an unknown version has to be refused, not mapped onto a data class.
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(testFixtures(project(":core:connection")))
}
