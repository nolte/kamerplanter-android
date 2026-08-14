import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.github.nolte.kamerplanter.core.connection"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    // InMemoryCredentialStore is needed by this module's own tests and by
    // :feature:settings's SettingsViewModelTest. Test source sets do not cross module
    // boundaries, so without fixtures the fake would have to be duplicated.
    testFixtures {
        enable = true
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
    // Storage only. This module holds what a connection *is* and where its two halves are
    // persisted — no Compose, no camera, no networking. Feature modules read it to learn
    // which instance to talk to and with which credential; the code that establishes a
    // connection stays in :feature:settings.
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
