import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.github.nolte.kamerplanter"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.nolte.kamerplanter"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // Hilt has to build the application object for an instrumented test, or every
        // binding the test wants to replace is already frozen by the time it runs.
        testInstrumentationRunner = "io.github.nolte.kamerplanter.HiltTestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        // Kept as a guard, not as a fix for a current finding. The original cause was
        // libuvccommon — a runtime dependency of the upstream libuvc POM bundling a
        // notification helper for consumers that run the camera from a foreground service.
        // The fork's POM (#14) declares only androidx.appcompat and com.elvishew:xlog, so
        // that transitive dependency is gone and the finding it produced cannot recur from
        // there. The suppression stays because the reasoning is unchanged: this app posts
        // no notifications, so declaring POST_NOTIFICATIONS would request a permission we
        // never use, which permission minimalism forbids. Remove it the moment this app
        // posts anything — at which point the permission belongs in the manifest.
        disable += "NotificationPermission"
    }

    testOptions {
        managedDevices {
            localDevices {
                // The device the acceptance criteria name (issues #1, #9, #10, #12), on the
                // newest API level that ships an ATD image — API 37 has none. ATD is a
                // stripped image without the Play stack or a launcher, which is what makes a
                // headless run fast; nothing under test needs either.
                //
                // This is a Pixel 7a *profile* — screen, density and form factor — not the
                // physical Pixel 7a. It says nothing about how the real hardware performs,
                // so a result from here is never reported as device verification.
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
    implementation(project(":core:network"))
    implementation(project(":feature:microscope"))
    implementation(project(":feature:pestdetection"))
    implementation(project(":feature:plants"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)

    // Supplies the empty activity the Compose test rule launches into. Debug-only, so it
    // never reaches a release manifest.
    debugImplementation(libs.compose.ui.test.manifest)
}
