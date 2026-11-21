import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ArtifactResolutionResult
import org.gradle.api.artifacts.result.ComponentArtifactsResult
import org.gradle.api.artifacts.result.ResolvedArtifactResult
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
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import java.io.File
import java.io.Serializable

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
 * published — obtained through Gradle's [ArtifactResolutionQuery] for
 * `org.gradle.maven.MavenPomArtifact`, which resolves and downloads the POM
 * whatever is on disk, instead of reading Gradle's private cache directory.
 *
 * The raw `<name>` is free text, so [LicenseNormalizer] collapses it to a
 * canonical identifier before it reaches the file. A module whose POM declares
 * no `<licenses>` block is reported as [LicenseIds.NOT_DECLARED]; no licence is
 * ever inferred for it.
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
     * Built by the build script from the runtime classpath's resolution graph
     * because a `ResolutionResult` is not a serializable task property and the
     * configuration cache rejects it. What crosses into the task is plain
     * strings, which serialize.
     */
    @get:Input
    abstract val coordinates: ListProperty<String>

    /**
     * Every POM the shipped modules are distributed with, one entry per module.
     *
     * Deliberately COORDINATE-KEYED rather than a bare file set: the files come
     * out of the resolution query and live under Gradle's identity-less
     * `metadata-<version>/descriptors` store, so their on-disk path says nothing
     * about which module they belong to. [pomCoordinates] therefore carries the
     * keys, and [PomIndex] is a pure function of the declared graph.
     *
     * `@InputFiles` + [PathSensitivity.RELATIVE] makes adding a library or a
     * changed POM re-run the task, while staying relocatable across CI runners.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pomFiles: ConfigurableFileCollection

    /**
     * The key structure of [pomFiles] as plain data: each entry names the
     * coordinate whose POM is at the same position in [pomFiles].
     *
     * The build script derives both from ONE ordered query result, so the task
     * can rely on that positional match. It fails the build if the two ever
     * disagree on length.
     */
    @get:Input
    abstract val pomCoordinates: ListProperty<LicensePom>

    /** Header comment written above the entries. */
    @get:Input
    abstract val header: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun writeInventory() {
        val files = pomFiles.files
        val declared = pomCoordinates.get()
        if (files.size != declared.size) {
            throw GradleException(
                "Licence POM inputs are inconsistent: ${files.size} files for ${declared.size} " +
                    "declared coordinates. The inventory cannot be built from mismatched inputs.",
            )
        }
        val poms = PomIndex(declared.mapIndexed { index, pom -> pom.coordinate to files.elementAt(index) })
        val entries =
            coordinates
                .get()
                .distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .map { coordinate ->
                    val licence = poms.licenceOf(coordinate)
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
 * Reads module licences out of the POM file of each module, keyed by the
 * coordinate the POM belongs to.
 *
 * The key is supplied, never derived from the file path: a POM returned by the
 * resolution query sits in a flat identity-less metadata store. Anything that
 * arrives without a key is rejected instead of guessed at.
 */
class PomIndex(
    entries: List<Pair<String, File>>,
) {
    private val byCoordinate: Map<String, File> = entries.toMap()

    private val resolutions: Map<String, LicenseResolution> by lazy {
        byCoordinate
            .mapValues { (_, pom) -> readLicence(pom) }
            .filterValues { it != null }
            .mapValues { it.value!! }
    }

    /**
     * The canonical id for a module, or [LicenseIds.NOT_DECLARED] when its POM
     * has no `<licenses>` block.
     */
    fun licenceOf(coordinate: String): String {
        if (!byCoordinate.containsKey(coordinate)) {
            // The module is in the shipped classpath, so its POM must have been
            // resolvable. A miss means the query did not return it, not that it
            // has no licence — fail loudly rather than write NOT_DECLARED.
            throw GradleException(
                "No POM metadata found for $coordinate. " +
                    "The licence inventory cannot be built without it.",
            )
        }
        return resolutions[coordinate]?.id ?: LicenseIds.NOT_DECLARED
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

/**
 * One module coordinate whose POM the resolution query returned.
 *
 * Covers the listed modules matched with the files. No path is stored here:
 * the files travel as [GenerateLicensesAssetTask.pomFiles], so an absolute CI
 * checkout path never becomes part of the task's serialized input.
 *
 * A plain data class so `@Input` can serialize it into the configuration cache.
 */
data class LicensePom(
    val coordinate: String,
) : Serializable

/**
 * Runs Gradle's POM resolution query for [components] and returns one entry per
 * module whose POM could be found, in a deterministic order.
 *
 * This replaces reading Gradle's private `caches/modules-2/files-2.1` layout: on
 * a CI runner the dependencies arrive from the build cache and that directory is
 * never populated, so the walk found nothing. The query asks Gradle for the POM
 * as an artifact of the component, which resolves (and downloads) it from the
 * repository when it is not present. A module whose POM the query cannot return
 * is omitted here and then fails the task by name, never silently downgraded.
 *
 * [from] is the project whose repositories serve the query: pass the project the
 * components were resolved for (`:app`), not the consuming one.
 */
fun resolvePoms(
    from: Project,
    components: Set<ComponentIdentifier>,
): List<ResolvedLicensePom> {
    if (components.isEmpty()) return emptyList()
    val result =
        from.dependencies
            .createArtifactResolutionQuery()
            .forComponents(components)
            .withArtifacts(MavenModule::class.java, setOf(MavenPomArtifact::class.java))
            .execute()
    val resolved = LinkedHashMap<String, File>()
    components.forEach { component ->
        val module = component as? ModuleComponentIdentifier ?: return@forEach
        val coordinate = "${module.group}:${module.module}:${module.version}"
        resolved[coordinate] = artifactOf(result, component) ?: return@forEach
    }
    // Sorted by coordinate so the list and the matching file collection are
    // deterministic across runs and runners.
    return resolved
        .map { (coordinate, file) -> ResolvedLicensePom(LicensePom(coordinate), file) }
        .sortedBy { it.pom.coordinate }
}

/** A POM as the query returned it: the coordinate to key it by, and the file. */
class ResolvedLicensePom(
    val pom: LicensePom,
    val file: File,
)

/** The first resolved POM for [component], or null when the query returned none. */
private fun artifactOf(
    result: ArtifactResolutionResult,
    component: ComponentIdentifier,
): File? =
    result
        .resolvedComponents
        .filterIsInstance<ComponentArtifactsResult>()
        .firstOrNull { it.id == component }
        ?.getArtifacts(MavenPomArtifact::class.java)
        ?.filterIsInstance<ResolvedArtifactResult>()
        ?.firstOrNull()
        ?.file
