package org.jetbrains.kmp.resolver

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.outputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.jetbrains.amper.dependency.resolution.MavenRepository
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.kmp.resolver.auth.BasicRepositoryCredentials
import org.jetbrains.kmp.resolver.auth.RepositoryCredentials
import org.jetbrains.kmp.resolver.http.RetryPolicy
import org.jetbrains.kmp.resolver.http.withAuthenticatingHttpClient

/**
 * Commands that generates a manifest to be consumed by Bazel in a repository rule to produce
 * repositories which exposes the dependencies that have been resolved to a Bazel module.
 */
class GenerateBazelManifestCommand : SuspendingCliktCommand("generate-bazel-manifest") {
    private val coordinates by
        option(
                "--coordinate",
                help =
                    "Maven coordinate to resolve, can be specified multiple times for resolving many coordinates at once.",
            )
            .multiple(required = true)

    private val platforms: Set<ResolutionPlatform> by
        option(
                "--platform",
                help =
                    "Platform to resolve the coordinates for, can be specified multiple times to resolve many platforms at once. " +
                        "`wasmjs` produces the klib variants of KMP libraries, `jvm` their jar variants along with JVM-only libraries.",
            )
            .choice(
                "wasmjs" to ResolutionPlatform.WASM_JS,
                "jvm" to ResolutionPlatform.JVM,
            )
            .multiple(required = true)
            .unique()

    private val substitutions: List<Pair<SubstitutionId, MultiplatformLibraryId>> by
        option(
                "--substitution",
                help =
                    "`group:artifact=group:artifact:version` substituting a library by another one, resolved in its place with its own " +
                        "artifacts and dependencies; substituting a KMP library covers its platform-specific variants. Can be specified multiple times.",
            )
            .convert {
                val (original, sub) = it.split("=", limit = 2)
                original to MultiplatformLibraryId.fromString(sub)
            }
            .multiple(required = false)

    private val outputManifest by
        option(
                "--output-manifest-file",
                help = "Path to the output manifest file.",
            )
            .convert { Path.of(it) }
            .required()

    private val repositories by
        option(
                "--repository",
                help =
                    "Maven repository URL, can be specified multiple times for resolving against many repositories.",
            )
            .multiple(required = true)

    private val repositoryCredentialsFile: Path? by
        option(
                "--repository-credentials-file",
                help =
                    """
                    Path to JSON repository credentials resolved by the caller, sent as basic authentication with the requests under
                    each repository URL.
                    """
                        .trimIndent(),
            )
            .convert { Path.of(it) }

    private val nodeExecutable: Path by
        option(
                "--node-executable",
                help =
                    "Path to the Node.js executable used to run npm when resolving klib-declared NPM dependencies.",
            )
            .convert { Path.of(it) }
            .required()

    private val npmCliJs: Path by
        option(
                "--npm-cli-js",
                help =
                    "Path to npm's `npm-cli.js` entry point, run with the Node.js executable (portable, unlike the `npm` wrapper scripts).",
            )
            .convert { Path.of(it) }
            .required()

    private val npmRegistry: String? by
        option(
            "--npm-registry",
            help =
                "NPM registry URL used to resolve klib-declared NPM dependencies. Defaults to https://registry.npmjs.org.",
        )

    private val npmPackageVersions: List<Pair<String, String>> by
        option(
                "--npm-package-version",
                help =
                    "Hardcoded `name=version` of an NPM package, resolving version conflicts between klibs manually, can be specified multiple times.",
            )
            .convert {
                val (name, version) = it.split("=", limit = 2)
                name to version
            }
            .multiple(required = false)

    private val allowedConcurrentConnections: Int by
        option(
                "--allowed-concurrent-connections",
                help = "Maximum number of concurrent artifact HTTP checks per repository host.",
            )
            .int()
            .default(32)

    private val requestTimeoutMillis: Long by
        option(
                "--request-timeout-ms",
                help = "HTTP request timeout in milliseconds when checking artifact URLs.",
            )
            .long()
            .default(30.seconds.inWholeMilliseconds)

    private val connectTimeoutMillis: Long by
        option(
                "--connect-timeout-ms",
                help =
                    "HTTP connect timeout in milliseconds, for artifact URL checks and downloads alike.",
            )
            .long()
            .default(10.seconds.inWholeMilliseconds)

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun run() {
        val repositoryCredentials =
            when (val credentialsFile = repositoryCredentialsFile) {
                null -> emptyList()
                else -> RepositoryCredentials.fromFile(credentialsFile)
            }
        val manifest =
            withAuthenticatingHttpClient(
                credentials = BasicRepositoryCredentials(repositoryCredentials),
                connectTimeout = connectTimeoutMillis.milliseconds,
            ) { httpClient ->
                val artifactResolver =
                    ArtifactUrlResolver(
                        httpClient = httpClient,
                        allowedConcurrentConnections = allowedConcurrentConnections,
                        requestTimeout = requestTimeoutMillis.milliseconds,
                        retryPolicy = RetryPolicy.DEFAULT,
                    )
                val resolver =
                    MultiplatformResolver(
                        cachePath = outputManifest.parent,
                        platforms = platforms,
                        repositories = repositories.map { MavenRepository(it) },
                        httpClient = httpClient,
                        artifactResolver = artifactResolver,
                        substitutions = substitutions.toMap(),
                        npmResolver =
                            NpmResolver(
                                nodeExecutable = nodeExecutable,
                                npmCliJs = npmCliJs,
                                registryUrl = npmRegistry,
                                packageVersionOverrides = npmPackageVersions.toMap(),
                                workDir = outputManifest.parent,
                            ),
                    )
                BazelManifest(
                    askedCoordinates = coordinates.sorted(),
                    askedRepositories = repositories.sorted(),
                    libraries = resolver.resolve(coordinates).asLibraries(),
                )
            }
        outputManifest.createParentDirectories()
        outputManifest.outputStream().use { output ->
            json.encodeToStream(manifest, output)
        }
    }

    companion object {
        private val json = Json {
            allowStructuredMapKeys = true
        }
    }
}

internal fun List<MultiplatformVariant>.asLibraries():
    Map<MultiplatformLibraryId, MultiplatformLibrary> = groupBy {
    it.id
}
    .mapValues { (id, variants) ->
        MultiplatformLibrary(
            id = id,
            variants = variants,
        )
    }
    .toSortedMap()

@Serializable
internal data class BazelManifest(
    val askedCoordinates: List<String>,
    val askedRepositories: List<String>,
    val libraries: Map<MultiplatformLibraryId, MultiplatformLibrary>,
)
