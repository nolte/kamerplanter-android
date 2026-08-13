plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))

        // detekt looks at src/{main,test}/{java,kotlin} and nothing else. Build-variant
        // source sets are where the per-variant ConnectionClient binding lives (R34), so
        // without this they would compile and ship unanalysed — a lint gate that is green
        // because it never looked. Directories that do not exist contribute nothing.
        source.from(
            files(
                "src/debug/kotlin",
                "src/release/kotlin",
                "src/testDebug/kotlin",
                "src/testRelease/kotlin",
            ),
        )
    }

    dependencies {
        "detektPlugins"(versionCatalog.findLibrary("detekt-formatting").get())
    }
}
