package org.jetbrains.kmp.resolver

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.outputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Commands that generates a manifest to be consumed by Bazel in a repository rule to produce repositories which exposes
 * the dependencies that have been resolved to a Bazel module.
 */
class GenerateBazelManifestCommand : SuspendingCliktCommand("generate-bazel-manifest") {
    private val coordinates by option(
        "--coordinate",
        help = "Maven coordinate to resolve, can be specified multiple times for resolving many coordinates at once.",
    ).multiple(required = true)

    private val substitutions: List<Pair<SubstitutionId, MultiplatformLibraryId>> by option(
        "--substitution",
        help = "Pair to substitute a group/artifact pair into another at resolution time, can be specified multiple times.",
    ).convert {
        val (original, sub) = it.split("=", limit = 2)
        original to MultiplatformLibraryId.fromString(sub)
    }.multiple(required = false)

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

    private val credentialHelpers: List<CredentialHelper> by option(
        "--credential-helper",
        help = "Bazel credential helper as `[<pattern>=]<path>`, using the syntax and the semantics of Bazel's " +
            "own `--credential_helper` flag so that `.bazelrc` values can be forwarded verbatim. Can be " +
            "specified multiple times. Takes precedence over --repository-credentials-file.",
    ).convert { parseCredentialHelper(it) }.multiple(required = false)

    private val credentialHelperTimeoutMillis: Long by option(
        "--credential-helper-timeout-ms",
        help = "Timeout of a credential helper invocation in milliseconds.",
    ).long().default(10.seconds.inWholeMilliseconds)

    private val workspaceDirectory: Path by option(
        "--workspace-directory",
        help = "Bazel workspace directory, used as the working directory of the credential helpers and to " +
            "expand the `%workspace%` prefix of their paths. Defaults to the current directory.",
    ).convert { Path.of(it) }.defaultLazy { Path.of("").toAbsolutePath() }

    private val nodeExecutable: Path by option(
        "--node-executable",
        help = "Path to the Node.js executable used to run npm when resolving klib-declared NPM dependencies.",
    ).convert { Path.of(it) }.required()

    private val npmCliJs: Path by option(
        "--npm-cli-js",
        help = "Path to npm's `npm-cli.js` entry point, run with the Node.js executable (portable, unlike the `npm` wrapper scripts).",
    ).convert { Path.of(it) }.required()

    private val npmRegistry: String? by option(
        "--npm-registry",
        help = "NPM registry URL used to resolve klib-declared NPM dependencies. Defaults to https://registry.npmjs.org.",
    )

    private val npmPackageVersions: List<Pair<String, String>> by option(
        "--npm-package-version",
        help = "Hardcoded `name=version` of an NPM package, resolving version conflicts between klibs manually, can be specified multiple times.",
    ).convert {
        val (name, version) = it.split("=", limit = 2)
        name to version
    }.multiple(required = false)

    private val allowedConcurrentConnections: Int by option(
        "--allowed-concurrent-connections",
        help = "Maximum number of concurrent artifact HTTP checks per repository host.",
    ).int().default(32)

    private val requestTimeoutMillis: Long by option(
        "--request-timeout-ms",
        help = "HTTP request timeout in milliseconds when checking artifact URLs.",
    ).long().default(30.seconds.inWholeMilliseconds)

    private val connectTimeoutMillis: Long by option(
        "--connect-timeout-ms",
        help = "HTTP connect timeout in milliseconds when checking artifact URLs.",
    ).long().default(10.seconds.inWholeMilliseconds)

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun run() {
        val fileCredentials = when (val credentialsFile = repositoryCredentialsFile) {
            null -> emptyMap()
            else -> RepositoryCredentials.fromFile(credentialsFile)
        }
        val effectiveNpmRegistry = npmRegistry ?: NpmResolver.DEFAULT_REGISTRY_URL
        // A credential helper mints credentials on demand, so it wins over the statically passed ones. This is
        // the precedence Bazel applies between credential helpers and `.netrc` for the downloads it performs.
        val credentials = fileCredentials + when {
            credentialHelpers.isEmpty() -> emptyMap()
            else -> CredentialHelperProvider(
                helpers = credentialHelpers,
                workspaceDirectory = workspaceDirectory,
                timeout = credentialHelperTimeoutMillis.milliseconds,
            ).credentialsFor(repositories + effectiveNpmRegistry)
        }
        val credentialsResolver = RepositoryCredentialsResolver(credentials.values)
        // Amper sends HTTP Basic auth natively, so a resolution only needing that is left running on Amper's own
        // client, exactly as it did before credentials could carry arbitrary headers.
        val credentialAwareHttpClient = when {
            credentialsResolver.requiresHeaderInjection ->
                CredentialAwareHttpClient.wrappingDefaultClient(credentialsResolver)

            else -> null
        }
        val artifactResolver = ArtifactUrlResolver(
            allowedConcurrentConnections = allowedConcurrentConnections,
            requestTimeout = requestTimeoutMillis.milliseconds,
            connectTimeout = connectTimeoutMillis.milliseconds,
        )
        val manifest = artifactResolver.use { artifactResolver ->
            val resolver = MultiplatformResolver(
                cachePath = outputManifest.parent,
                repositories = repositories.withRepositoryCredentials(credentials),
                artifactResolver = artifactResolver,
                substitutions = substitutions.toMap(),
                npmResolver = NpmResolver(
                    nodeExecutable = nodeExecutable,
                    npmCliJs = npmCliJs,
                    registryUrl = npmRegistry,
                    packageVersionOverrides = npmPackageVersions.toMap(),
                    workDir = outputManifest.parent,
                    credentials = credentialsResolver,
                ),
                credentialAwareHttpClient = credentialAwareHttpClient,
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

internal fun List<MultiplatformVariant>.asLibraries(): Map<MultiplatformLibraryId, MultiplatformLibrary> =
    groupBy { it.id }.mapValues { (id, variants) ->
        MultiplatformLibrary(
            id = id,
            variants = variants,
        )
    }.toSortedMap()

@Serializable
internal data class BazelManifest(
    val askedCoordinates: List<String>,
    val askedRepositories: List<String>,
    val libraries: Map<MultiplatformLibraryId, MultiplatformLibrary>,
)
