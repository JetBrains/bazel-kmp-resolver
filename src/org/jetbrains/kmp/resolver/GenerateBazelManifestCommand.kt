package org.jetbrains.kmp.resolver

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.outputStream

/**
 * Commands that generates a manifest to be consumed by Bazel in a repository rule to produce repositories which exposes
 * the dependencies that have been resolved to a Bazel module.
 */
class GenerateBazelManifestCommand : SuspendingCliktCommand("generate-bazel-manifest") {
    private val coordinates by option(
        "--coordinate",
        help = "Maven coordinate to resolve, can be specified multiple times for resolving many coordinates at once.",
    ).multiple(required = true)

    private val outputManifest by option(
        "--output-manifest-file",
        help = "Path to the output manifest file.",
    ).convert { Path.of(it) }.required()

    private val repositories by option(
        "--repository",
        help = "Maven repository URL, can be specified multiple times for resolving against many repositories.",
    ).multiple(required = true)

    private val repositoryCredentialsFile: Path? by option(
        "--repository-credentials-file",
        help = "Path to JSON repository credentials resolved by the caller.",
    ).convert { Path.of(it) }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun run() {
        val credentials = when (val credentialsFile = repositoryCredentialsFile) {
            null -> emptyMap()
            else -> RepositoryCredentials.fromFile(credentialsFile)
        }
        val resolver = MultiplatformResolver(
            cachePath = outputManifest.parent,
            repositories = repositories.withRepositoryCredentials(credentials),
        )
        val manifest = BazelManifest(
            askedCoordinates = coordinates.sorted(),
            askedRepositories = repositories.sorted(),
            libraries = resolver.resolveMultiplatformComponentsOf(coordinates).associateBy { it.id }.toSortedMap(),
        )
        outputManifest.createParentDirectories()
        outputManifest.outputStream().use { output ->
            json.encodeToStream(manifest, output)
        }
    }

    companion object {
        private val json = Json {
            allowStructuredMapKeys = true
            prettyPrint = true
        }
    }
}

@Serializable
private data class BazelManifest(
    val askedCoordinates: List<String>,
    val askedRepositories: List<String>,
    val libraries: Map<MultiplatformLibraryId, MultiplatformLibrary>,
)
