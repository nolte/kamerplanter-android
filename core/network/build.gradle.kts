import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.github.nolte.kamerplanter.core.network"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        // Deliberately the generator's own output rather than a hand-kept copy. `app`
        // builds with isMinifyEnabled = true, and kotlinx.serialization reaches its
        // serializers through `Companion` members and `$$serializer` classes that R8
        // cannot see being used — stripped, every response fails to deserialize in the
        // release build only, which no debug build and no unit test reproduces.
        //
        // These rules name the generated package literally, and R8 treats a -keep on a
        // package that matches nothing as a silent no-op. Pointing at the generated file
        // means a change to `packageName` below rewrites the rules in the same step,
        // instead of leaving a copy that still looks right and protects nothing.
        consumerProguardFiles("src/generated/kotlin/proguard-rules.pro")
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

    // The generated client is checked in but kept out of src/main deliberately: detekt's
    // default source set is src/{main,test}/{java,kotlin}, so a separate root keeps
    // machine-written code out of a gate that only judges hand-written code — without
    // needing a per-rule exclude list that would silently also cover src/main.
    sourceSets {
        getByName("main") {
            kotlin.srcDir("src/generated/kotlin")
        }
    }
}

/** Isolates the generator from every compile and runtime classpath (ADR 0001, R-GEN-5). */
val openApiGenerator = configurations.create("openApiGenerator") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // Which instance to talk to and with which credential. `api`, not `implementation`:
    // NetworkConnectionClient implements the ConnectionClient declared over there, so the
    // seam and its request/result types are part of what this module offers.
    api(project(":core:connection"))

    // The OpenAPI-generated kamerplanter client (ADR 0001) lands in this module;
    // Retrofit/OkHttp/serialization are its runtime and part of this module's API.
    api(libs.retrofit)
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.converter.kotlinx.serialization)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // The in-memory CredentialStore, so refresh and the plant list can both be driven
    // without a Keystore.
    testImplementation(testFixtures(project(":core:connection")))

    add(openApiGenerator.name, libs.openapi.generator.cli)
}

// --- Generated kamerplanter API client (spec/api/openapi-client-integration/) ------------
//
// The client is generated from a vendored, digest-pinned schema and checked in, so an
// ordinary build compiles it like any other source and only a schema bump regenerates it.
// Three tasks, each mapping to a requirement:
//
//   verifyOpenApiSchema     R-GEN-3  the vendored document still matches its sha256
//   generateApiClient       R-GEN-4  regenerate the checked-in client (manual)
//   checkApiClientUpToDate  R-GEN-7  the checked-in client still matches the schema
//
// Kept out of the `android { }` block because none of it is Android-specific: it is a
// source-generation pipeline that happens to live in an Android library module.

val generatedClientDir = layout.projectDirectory.dir("src/generated/kotlin")
val openApiDir = layout.projectDirectory.dir("openapi")

val verifyOpenApiSchema = tasks.register<VerifyOpenApiSchema>("verifyOpenApiSchema") {
    group = "verification"
    description = "Verifies the vendored OpenAPI document against its recorded sha256."
    schema.set(openApiDir.file("openapi.json"))
    provenance.set(openApiDir.file("provenance.json"))
    verifiedDigest.set(layout.buildDirectory.file("openapi/verified-sha256.txt"))
}

val prepareOpenApiDocument = tasks.register<PrepareOpenApiDocument>("prepareOpenApiDocument") {
    description = "Reduces the vendored document to the API surface this app binds against."
    dependsOn(verifyOpenApiSchema)
    schema.set(openApiDir.file("openapi.json"))
    preparedSchema.set(layout.buildDirectory.file("openapi/prepared.json"))
}

/** Shared so the checked-in client and the drift check cannot be generated differently. */
fun GenerateApiClient.configureGeneration() {
    preparedSchema.set(prepareOpenApiDocument.flatMap { it.preparedSchema })
    ignoreFile.set(openApiDir.file(".openapi-generator-ignore"))
    generatorClasspath.from(openApiGenerator)
    packageName.set("io.github.nolte.kamerplanter.core.network.generated")
}

tasks.register<GenerateApiClient>("generateApiClient") {
    group = "build"
    description = "Regenerates the checked-in kamerplanter API client from the vendored schema."
    configureGeneration()
    outputDirectory.set(generatedClientDir)
}

val regenerateApiClientForCheck =
    tasks.register<GenerateApiClient>("regenerateApiClientForCheck") {
        description = "Generates the client into a scratch tree for the drift check."
        configureGeneration()
        outputDirectory.set(layout.buildDirectory.dir("openapi/expected-client"))
    }

val checkApiClientUpToDate =
    tasks.register<CheckGeneratedClientUpToDate>("checkApiClientUpToDate") {
        group = "verification"
        description = "Fails when the checked-in API client has drifted from the schema."
        // Explicit: the generator's output directory is @Internal (see GenerateApiClient),
        // so Gradle cannot infer this ordering from the property wiring alone.
        dependsOn(regenerateApiClientForCheck)
        checkedInClient.set(generatedClientDir)
        freshlyGenerated.set(regenerateApiClientForCheck.flatMap { it.outputDirectory })
    }

// `./gradlew check` picks both gates up. That is not what CI runs, though — CI calls
// `task lint` / `task test` / `task assemble`, none of which reach `check`. The Taskfile's
// verify:openapi-client target is what actually enforces these, chained from `lint` the
// same way verify:page-size is chained from `assemble`.
tasks.named("check") {
    dependsOn(verifyOpenApiSchema, checkApiClientUpToDate)
}
