pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack's 3.3.3 build of AUSBC is incomplete (its :libuvc NDK step fails on
        // JitPack's builders, so libuvc/libnative were never published). The two missing
        // artifacts are vendored under original coordinates; provenance is documented in
        // third_party/ausbc-m2/README.md. This repo must stay ahead of JitPack: JitPack
        // serves a libnative-3.3.3.pom whose AAR 404s.
        maven(url = uri("third_party/ausbc-m2")) {
            content {
                includeModule("com.github.jiangdongguo.AndroidUSBCamera", "libuvc")
                includeModule("com.github.jiangdongguo.AndroidUSBCamera", "libnative")
            }
        }
        // UVC capture library (ADR 0001): com.github.jiangdongguo.AndroidUSBCamera
        maven("https://jitpack.io") {
            content {
                includeGroupByRegex("com\\.github\\..*")
            }
        }
    }
}

rootProject.name = "kamerplanter-android"

include(":app")
include(":core:network")
include(":feature:microscope")
include(":feature:settings")
