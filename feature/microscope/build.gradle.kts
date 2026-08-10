import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.nolte.kamerplanter.feature.microscope"
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
    // The UVC engine (libs.ausbc) is added here — and only here — when the capture
    // feature is implemented. Isolation rule: ADR 0001, "Isolation rule for the UVC
    // dependency". The rest of the app talks to MicroscopeCamera.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)

    testImplementation(libs.junit)
}
