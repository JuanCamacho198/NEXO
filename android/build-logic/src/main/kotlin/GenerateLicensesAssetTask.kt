import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Writes the third-party licence inventory of a resolved runtime classpath as
 * `licenses.txt`, one `group:name:version<TAB>LICENCE_ID` per line, sorted.
 *
 * Group and artifact come from the RESOLVED COMPONENT GRAPH, not from parsing
 * artifact file names. Gradle's cache names every artifact after its module
 * (`coil-network-okhttp-3.1.0.jar`), but that never encodes the group — you
 * cannot read `androidx.compose.material3` off `material3-android-1.3.0.aar`.
 *
 * The licence is read from each module's own POM, which is what the author
 * published. The raw `<name>` is free text, so [LicenseNormalizer] collapses it
 * to a canonical identifier before it reaches the file. A module whose POM
 * declares no `<licenses>` block is reported as [LicenseIds.NOT_DECLARED]; no
 * licence is ever inferred for it.
 *
 * Lives in build-logic rather than the consuming build script because this
 * repository enables the configuration cache: a `doLast` closure captures the
 * build script object, and a task class declared inside a Kotlin build script is
 * a non-static inner class. Neither can be serialized. Every property below is a
 * plain Gradle provider or a file set, which is what makes it serializable.
 */
abstract class GenerateLicensesAssetTask : DefaultTask() {
    /**
     * Already-filtered `group:name:version` coordinates, one per shipped module.
     *
     * The resolution graph is walked by the build script because a
     * `ResolutionResult` is not a serializable task property and the
     * configuration cache rejects it. What crosses into the task is plain
     * strings, which serialize.
     */
    @get:Input
    abstract val coordinates: ListProperty<String>

    /**
     * Gradle's POM metadata for those modules, shaped
     * `<group>/<name>/<version>/<sha1>/<name>-<version>.pom` under its cache.
     *
     * Passed as a file set (not parsed content) so the task stays
     * configuration-cache safe and incremental: adding a library changes the
     * set, editing a POM re-runs the task.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pomFiles: ConfigurableFileCollection

    /** Header comment written above the entries. */
    @get:Input
    abstract val header: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun writeInventory() {
        val poms = PomIndex(pomFiles.files)
        val entries =
            coordinates
                .get()
                .distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .map { coordinate ->
                    val parts = coordinate.split(':')
                    val licence =
                        if (parts.size < 3) {
                            LicenseIds.NOT_DECLARED
                        } else {
                            poms.licenceOf(parts[0], parts[1], parts.drop(2).joinToString(":"))
                        }
                    "$coordinate\t$licence"
                }

        val out = outputDirectory.get().file("licenses.txt").asFile
        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                appendLine(header.get())
                appendLine("# Entries: ${entries.size}")
                appendLine("# Format: group:name:version<TAB>LICENCE_ID (see LicenseNormalizer)")
                // One comment line per identifier present, so the raw POM names
                // behind a normalised id stay auditable straight from the APK.
                poms.rawNamesByLicence().toSortedMap().forEach { (id, names) ->
                    appendLine("# $id <- ${names.sorted().joinToString(" | ")}")
                }
                entries.forEach { appendLine(it) }
            },
        )
    }
}

/**
 * Reads module licences out of Gradle's cached POM files.
 *
 * Gradle stores each module's POM under an opaque SHA-1 directory, so the file
 * is matched by its cache shape (`group/name/version`) instead of one exact
 * path. Anything not at that depth is ignored, so an unexpected layout cannot
 * produce a wrong match.
 */
class PomIndex(
    files: Collection<File>,
) {
    private val byShape: Map<String, List<File>> =
        files
            .filter { it.isFile && it.name.endsWith(".pom") }
            .mapNotNull { file -> shapeOf(file)?.let { shape -> shape to file } }
            .groupBy({ it.first }, { it.second })

    private val resolutions: Map<String, LicenseResolution> by lazy {
        byShape
            .mapValues { (_, poms) -> readLicence(poms.first()) }
            .filterValues { it != null }
            .mapValues { it.value!! }
    }

    /**
     * The canonical id for a module, or [LicenseIds.NOT_DECLARED] when its POM
     * has no `<licenses>` block.
     */
    fun licenceOf(
        group: String,
        name: String,
        version: String,
    ): String {
        val shape = "$group/$name/$version"
        if (!byShape.containsKey(shape)) {
            // The module is in the shipped classpath, so Gradle read its POM to
            // resolve it. A miss means the cache moved, not that it has no
            // licence — fail loudly rather than write NOT_DECLARED.
            throw GradleException(
                "No POM metadata found for $group:$name:$version. " +
                    "The licence inventory cannot be built without it.",
            )
        }
        return resolutions[shape]?.id ?: LicenseIds.NOT_DECLARED
    }

    /** Raw POM `<name>` text per normalised id, for the audit comment lines. */
    fun rawNamesByLicence(): Map<String, Set<String>> =
        resolutions
            .values
            .filter { it.rawName.isNotEmpty() }
            .groupBy({ it.id }, { it.rawName })
            .mapValues { (_, names) -> names.toSet() }

    private companion object {
        private val LICENSES_BLOCK = Regex("<licenses>([\\s\\S]*?)</licenses>", RegexOption.IGNORE_CASE)
        private val NAME_TAG = Regex("<name>([\\s\\S]*?)</name>", RegexOption.IGNORE_CASE)
        private val URL_TAG = Regex("<url>([\\s\\S]*?)</url>", RegexOption.IGNORE_CASE)
        private val ENTITY = Regex("&(amp|lt|gt|quot|apos|#\\d+);")

        /** `.../<group>/<name>/<version>/<hash>/<artifact>.pom` -> `group/name/version`. */
        fun shapeOf(file: File): String? {
            val hashDir = file.parentFile ?: return null
            val versionDir = hashDir.parentFile ?: return null
            val nameDir = versionDir.parentFile ?: return null
            val groupDir = nameDir.parentFile ?: return null
            // Deeper caches use a 40-char SHA-1, but an older entry can carry a
            // 39-char directory. Both are hex and both hold the same POM, so the
            // shape only rejects a directory that cannot be a hash at all.
            val looksLikeHash = hashDir.name.length in 32..40 && hashDir.name.all { it in '0'..'9' || it in 'a'..'f' }
            if (!looksLikeHash) return null
            return "${groupDir.name}/${nameDir.name}/${versionDir.name}"
        }

        /** Null when the POM exists but declares no `<licenses>` block. */
        fun readLicence(pom: File): LicenseResolution? {
            val xml = runCatching { pom.readText() }.getOrNull() ?: return null
            val block = LICENSES_BLOCK.find(xml)?.groupValues?.get(1) ?: return null
            val names =
                NAME_TAG
                    .findAll(block)
                    .map { unescape(it.groupValues[1].trim()) }
                    .filter { it.isNotEmpty() }
                    .toList()
            val urls =
                URL_TAG
                    .findAll(block)
                    .map { unescape(it.groupValues[1].trim()) }
                    .filter { it.isNotEmpty() }
                    .toList()
            return LicenseNormalizer.normalize(
                rawName = names.firstOrNull(),
                rawUrl = urls.firstOrNull(),
            )
        }

        fun unescape(value: String): String =
            ENTITY.replace(value) { match ->
                when (val token = match.groupValues[1]) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    else ->
                        token
                            .removePrefix("#")
                            .toIntOrNull()
                            ?.let { codePoint -> String(Character.toChars(codePoint)) }
                            ?: match.value
                }
            }
    }
}
