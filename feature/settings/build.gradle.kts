import com.android.build.api.variant.HasUnitTestBuilder
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

// The ConnectionClient binding is the one thing that differs per build variant: the fake
// lives in src/debug/, the placeholder in src/release/ (R34). AGP builds a unit-test
// variant only for the debug build type by default, which would leave the release half of
// that split — the half that must NOT be able to see the fake — compiled but never tested.
// Turning the release unit-test variant on makes src/test/ compile against both variants,
// so a reference from a variant-independent test back into src/debug/ fails the gate
// instead of passing it by accident.
androidComponents {
    beforeVariants { variant ->
        (variant as HasUnitTestBuilder).enableUnitTest = true
    }
}

dependencies {
    // The device-camera QR scanner lives here — and only here. The rest of the app
    // pairs through the app-owned PairingClient seam, never through CameraX/ML Kit.
    // (This is the phone camera, distinct from the USB/UVC camera in :feature:microscope.)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
