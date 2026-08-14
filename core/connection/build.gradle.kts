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

    // `api`, not `implementation`: ConnectionStore.connection is a public `Flow`, so the
    // type belongs to this module's API surface and has to travel with the dependency.
    //
    // Consumers would compile without it — coroutines reaches the classpath transitively
    // through AndroidX regardless, verified by removing this and rebuilding. That is
    // precisely the problem: the transitive path resolves 1.9.0, not the 1.11.0 pinned in
    // the catalog. Leaning on it means compiling against whatever version the next AndroidX
    // bump happens to drag in — chosen nowhere, reviewed by no one.
    //
    // `-core` rather than `-android`: this module uses Flow, not the Android dispatcher.
    api(libs.kotlinx.coroutines.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
