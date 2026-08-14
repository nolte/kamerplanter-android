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
}
