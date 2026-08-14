import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/**
 * R-GEN-3: fails the build when the vendored OpenAPI document does not match the `sha256`
 * its provenance file records.
 *
 * The document is a release asset copied into this repository, so nothing about it is
 * self-verifying — a truncated download, a hand-edit, or a swap for a different release
 * all leave a file that still parses as valid OpenAPI and still generates a client. Only
 * the digest tells them apart, and R-GEN-6 wants schema, tag and digest to move together
 * in one reviewable commit.
 */
@CacheableTask
abstract class VerifyOpenApiSchema : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val schema: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val provenance: RegularFileProperty

    /** Written so the task is up-to-date checkable; content is the verified digest. */
    @get:OutputFile
    abstract val verifiedDigest: RegularFileProperty

    @TaskAction
    fun verify() {
        val schemaFile = schema.get().asFile
        val recorded = readProvenance(provenance.get().asFile)
        val expected = recorded["sha256"] as? String
            ?: throw GradleException("${provenance.get().asFile} records no sha256.")
        val actual = sha256(schemaFile)

        if (actual != expected) {
            throw GradleException(
                """
                Vendored OpenAPI document does not match its recorded provenance.
                  file:     ${schemaFile.path}
                  expected: $expected
                  actual:   $actual
                Re-download the asset for release ${recorded["releaseTag"]}, or update the
                provenance file in the same commit that updates the schema (R-GEN-6).
                """.trimIndent(),
            )
        }
        verifiedDigest.get().asFile.writeText(actual)
        logger.lifecycle("OpenAPI schema matches ${recorded["releaseTag"]} ($expected).")
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun readProvenance(file: File): Map<String, Any?> =
            JsonSlurper().parse(file, Charsets.UTF_8.name()) as Map<String, Any?>

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Reduces the vendored document to the app's API surface before generation, per
 * [OpenApiDocumentPreparer]. Output is a build artifact, never checked in — the vendored
 * document stays the single reviewable source (R-GEN-1).
 */
@CacheableTask
abstract class PrepareOpenApiDocument : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val schema: RegularFileProperty

    @get:OutputFile
    abstract val preparedSchema: RegularFileProperty

    @Suppress("UNCHECKED_CAST")
    @TaskAction
    fun prepare() {
        val parsed = JsonSlurper()
            .parse(schema.get().asFile, Charsets.UTF_8.name()) as Map<String, Any?>
        val (document, summary) = OpenApiDocumentPreparer.prepare(parsed)

        preparedSchema.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.toJson(document), Charsets.UTF_8)
        }

        logger.lifecycle(
            "Prepared OpenAPI document: ${summary.paths} paths, ${summary.operations} " +
                "operations, ${summary.schemas} schemas " +
                "(${summary.binaryFieldsNormalized} binary fields normalized, " +
                "${summary.cookieParametersRemoved} cookie parameters removed).",
        )
    }
}

/**
 * R-GEN-4: regenerates the checked-in client from the prepared document.
 *
 * Not part of the normal build. The client is checked in so that a schema bump shows up as
 * a reviewable diff (R-GEN-6) and so an ordinary build needs no code generation at all;
 * `checkApiClientUpToDate` is what keeps the checked-in copy honest (R-GEN-7).
 *
 * Deliberately does not declare the generated tree as a task output: it is also the
 * module's source directory, and declaring it would make Gradle demand that every
 * compilation depend on regeneration — turning a maintenance task into a build step.
 */
abstract class GenerateApiClient : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val preparedSchema: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val ignoreFile: RegularFileProperty

    @get:Classpath
    abstract val generatorClasspath: ConfigurableFileCollection

    @get:Input
    abstract val packageName: Property<String>

    @get:Internal
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun generate() {
        val target = outputDirectory.get().asFile

        // A schema bump that drops a model leaves its file behind otherwise, and a stale
        // DTO still compiles — the drift gate would flag it, but only as an unexplained
        // diff. Starting from empty keeps the tree a pure function of the schema.
        fileSystemOperations.delete { delete(target) }

        execOperations.javaexec {
            classpath = generatorClasspath
            mainClass.set("org.openapitools.codegen.OpenAPIGenerator")
            args(
                "generate",
                "-i", preparedSchema.get().asFile.path,
                "-g", "kotlin",
                "-o", target.path,
                "--library", "jvm-retrofit2",
                "--ignore-file-override", ignoreFile.get().asFile.path,
                // Free-form JSON (`additionalProperties: true`) otherwise becomes
                // Map<String, Any>, which kotlinx.serialization cannot serialize — it does
                // not compile at all.
                "--type-mappings", "AnyType=kotlinx.serialization.json.JsonElement",
                "--import-mappings",
                "kotlinx.serialization.json.JsonElement=kotlinx.serialization.json.JsonElement",
                "--additional-properties",
                listOf(
                    "serializationLibrary=kotlinx_serialization",
                    "packageName=${packageName.get()}",
                    "useCoroutines=true",
                    // Emit straight into the module's generated source root instead of the
                    // generator's default src/main/kotlin sub-tree.
                    "sourceFolder=",
                ).joinToString(","),
            )
        }
    }
}

/**
 * R-GEN-7: fails when the checked-in client no longer matches what the pinned schema
 * generates.
 *
 * Without this the two drift apart silently and in the dangerous direction: the checked-in
 * client keeps compiling, so nothing complains, while it slowly stops describing the API
 * the app actually talks to. Compares trees rather than shelling out to `git diff`, so it
 * reports the same result on a dirty working copy as it does in CI.
 */
abstract class CheckGeneratedClientUpToDate : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val checkedInClient: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val freshlyGenerated: DirectoryProperty

    @TaskAction
    fun check() {
        val checkedIn = checkedInClient.get().asFile
        val fresh = freshlyGenerated.get().asFile

        val checkedInFiles = relativeFiles(checkedIn)
        val freshFiles = relativeFiles(fresh)

        val missing = (freshFiles - checkedInFiles).sorted()
        val stale = (checkedInFiles - freshFiles).sorted()
        val changed = checkedInFiles.intersect(freshFiles)
            .filterNot { File(checkedIn, it).readBytes().contentEquals(File(fresh, it).readBytes()) }
            .sorted()

        if (missing.isEmpty() && stale.isEmpty() && changed.isEmpty()) {
            logger.lifecycle("Generated API client is in sync with the vendored schema.")
            return
        }

        throw GradleException(
            buildString {
                appendLine("The checked-in API client does not match the vendored schema.")
                appendLine("Run './gradlew :core:network:generateApiClient' and commit the result.")
                if (missing.isNotEmpty()) {
                    appendLine("  missing (schema has them, repository does not):")
                    missing.take(REPORT_LIMIT).forEach { appendLine("    $it") }
                    if (missing.size > REPORT_LIMIT) appendLine("    … ${missing.size - REPORT_LIMIT} more")
                }
                if (stale.isNotEmpty()) {
                    appendLine("  stale (repository has them, schema does not):")
                    stale.take(REPORT_LIMIT).forEach { appendLine("    $it") }
                    if (stale.size > REPORT_LIMIT) appendLine("    … ${stale.size - REPORT_LIMIT} more")
                }
                if (changed.isNotEmpty()) {
                    appendLine("  differing content:")
                    changed.take(REPORT_LIMIT).forEach { appendLine("    $it") }
                    if (changed.size > REPORT_LIMIT) appendLine("    … ${changed.size - REPORT_LIMIT} more")
                }
            }.trimEnd(),
        )
    }

    private fun relativeFiles(root: File): Set<String> =
        root.walkTopDown().filter { it.isFile }.map { it.toRelativeString(root) }.toSet()

    private companion object {
        const val REPORT_LIMIT = 20
    }
}
