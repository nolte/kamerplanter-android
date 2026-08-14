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
        // UVC capture library (ADR 0001): com.github.nolte.AndroidUSBCamera, our fork of
        // jiangdongguo/AndroidUSBCamera carrying 16 KB-aligned native libraries (#14).
        maven("https://jitpack.io") {
            content {
                includeGroupByRegex("com\\.github\\..*")
            }
        }
    }
}

rootProject.name = "kamerplanter-android"

include(":app")
include(":core:connection")
include(":core:network")
include(":feature:microscope")
include(":feature:plants")
include(":feature:settings")
