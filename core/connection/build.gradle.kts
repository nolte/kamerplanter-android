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

    // Declared for two separate reasons, because neither alone covers it.
    //
    // Declared at all: without it this module still compiled — coroutines arrives
    // transitively through AndroidX — but against 1.9.0, while the catalog pins 1.11.0.
    // Measured on debugCompileClasspath: 1.9.0 without this line, 1.11.0 with it. The
    // version was being chosen by whatever AndroidX dragged in, not by the catalog.
    //
    // `api` rather than `implementation`: ConnectionStore.connection is a public `Flow`, so
    // the type is part of this module's API surface. Note this does *not* currently change
    // any consumer's resolved version — :feature:settings declares coroutines-android
    // itself, whose BOM already pulls core to 1.11.0. It matters the day a consumer stops
    // declaring coroutines of its own, which is exactly when nobody would think to look.
    //
    // `-core` rather than `-android`: this module uses Flow, not the Android dispatcher.
    api(libs.kotlinx.coroutines.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
