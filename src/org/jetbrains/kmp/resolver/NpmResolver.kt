package org.jetbrains.kmp.resolver

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.amper.processes.runProcessAndCaptureOutput
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.*

private val json = Json {
    ignoreUnknownKeys = true
}

/**
 * `package.json` embedded at the root of a klib, declaring the NPM dependencies its library requires at runtime.
 */
@Serializable
internal data class EmbeddedNpmManifest(
    val name: String,
    val version: String? = null,
    val dependencies: Map<String, String> = emptyMap(),
)

/**
 * Reads the NPM manifest embedded at the root of the given [klib], if any.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun readEmbeddedNpmManifest(klib: Path): EmbeddedNpmManifest? = ZipFile(klib.toFile()).use { zip ->
    when (val entry = zip.getEntry("package.json")) {
        null -> null
        else -> zip.getInputStream(entry).use { input -> json.decodeFromStream<EmbeddedNpmManifest>(input) }
    }
}

/**
 * A single klib variant asking for the resolution of the NPM dependencies of its embedded manifest.
 */
internal data class NpmResolutionRequest(
    val variantId: MultiplatformLibraryId,
    val manifest: EmbeddedNpmManifest,
)

/**
 * Resolves the NPM dependencies declared by klib-embedded manifests.
 *
 * All requests are aggregated into a single NPM workspaces layout (one workspace member per klib) on which a single
 * hermetic `npm install --package-lock-only` run resolves every dependency at once, so that the whole manifest agrees
 * on a single version per NPM package. The resulting `package-lock.json` is then walked back to attribute to each
 * klib its own transitive NPM closure.
 *
 * The resolution is hermetic and never incremental: the workspace layout is recreated from scratch on every run, npm
 * is pointed at a fresh cache and empty user/global configurations, scripts are never executed, and no package
 * tarball is ever downloaded (`--package-lock-only`).
 */
internal class NpmResolver(
    private val nodeExecutable: Path,
    private val npmCliJs: Path,
    registryUrl: String?,
    private val packageVersionOverrides: Map<String, String>,
    private val workDir: Path,
    private val credentials: RepositoryCredentialsResolver = RepositoryCredentialsResolver(emptyList()),
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val registryUrl: String = registryUrl ?: DEFAULT_REGISTRY_URL

    internal suspend fun resolve(requests: List<NpmResolutionRequest>): Map<MultiplatformLibraryId, List<NpmMultiplatformLibraryArtifact>> =
        when {
            requests.isEmpty() -> emptyMap()
            else -> resolveWorkspace(requests)
        }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun resolveWorkspace(requests: List<NpmResolutionRequest>): Map<MultiplatformLibraryId, List<NpmMultiplatformLibraryArtifact>> {
        val membersByDirectory = workspaceMembersByDirectory(requests)
        val workspacePath = createWorkspaceLayout(membersByDirectory)
        runNpmInstall(workspacePath)
        val lock = workspacePath.resolve("package-lock.json").inputStream().use { input ->
            json.decodeFromStream<NpmPackageLock>(input)
        }
        require(lock.lockfileVersion >= 2) {
            "Unsupported package-lock.json lockfileVersion ${lock.lockfileVersion}, expected 2 or newer"
        }
        val artifactsByVariant = membersByDirectory.entries.associate { (directory, request) ->
            request.variantId to lock.closureArtifacts(memberPath = "packages/$directory", request = request)
        }
        requireSingleVersionPerPackage(artifactsByVariant)
        return artifactsByVariant
    }

    private fun workspaceMembersByDirectory(requests: List<NpmResolutionRequest>): Map<String, NpmResolutionRequest> {
        val duplicatedNames = requests.groupBy { it.manifest.name }.filterValues { it.size > 1 }
        require(duplicatedNames.isEmpty()) {
            buildString {
                appendLine("Multiple klibs embed an NPM manifest with the same package name:")
                duplicatedNames.forEach { (name, duplicates) ->
                    appendLine("\t[$name] embedded by: ${duplicates.joinToString(", ") { it.variantId.gav }}")
                }
            }
        }
        val requestedDependencyNames = requests.flatMap { it.manifest.dependencies.keys }.toSet()
        val embeddedNamesUsedAsDependencies =
            requests.map { it.manifest.name }.filter { it in requestedDependencyNames }
        require(embeddedNamesUsedAsDependencies.isEmpty()) {
            "NPM packages embedded in klibs cannot also be depended upon by other klibs, but got: $embeddedNamesUsedAsDependencies"
        }
        return requests.sortedBy { it.manifest.name }.associateBy { request ->
            request.manifest.name.sanitizedAsDirectoryName()
        }.also { membersByDirectory ->
            require(membersByDirectory.size == requests.size) {
                "NPM workspace member directory name collision between: ${requests.joinToString(", ") { it.manifest.name }}"
            }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun createWorkspaceLayout(membersByDirectory: Map<String, NpmResolutionRequest>): Path {
        val workspacePath = workDir.resolve("npm-workspace")
        when {
            workspacePath.exists() -> workspacePath.deleteRecursively()
            else -> Unit
        }
        workspacePath.createDirectories()
        workspacePath.resolve("package.json").writeText(
            buildJsonObject {
                put("name", "kmp-npm-resolution")
                put("private", true)
                putJsonArray("workspaces") { add("packages/*") }
                if (packageVersionOverrides.isNotEmpty()) {
                    putJsonObject("overrides") {
                        packageVersionOverrides.forEach { (name, version) -> put(name, version) }
                    }
                }
            }.toString(),
        )
        membersByDirectory.forEach { (directory, request) ->
            val memberPath = workspacePath.resolve("packages").resolve(directory).createDirectories()
            memberPath.resolve("package.json").writeText(
                buildJsonObject {
                    put("name", request.manifest.name)
                    put("version", request.manifest.version ?: "0.0.0")
                    putJsonObject("dependencies") {
                        request.manifest.dependencies.forEach { (name, range) -> put(name, range) }
                    }
                }.toString(),
            )
        }
        return workspacePath
    }

    private suspend fun runNpmInstall(workspacePath: Path) {
        val npmCachePath = workDir.resolve("npm-cache")
        val emptyGlobalConfig = workspacePath.resolve(".empty-globalconfig").createFile()
        val userConfig = createUserConfig()
        try {
            val command = listOf(
                nodeExecutable.absolutePathString(),
                npmCliJs.absolutePathString(),
                "install",
                "--ignore-scripts",
                "--package-lock-only",
                "--no-audit",
                "--no-fund",
                "--progress=false",
                "--loglevel=error",
                "--registry=$registryUrl",
                "--cache=${npmCachePath.absolutePathString()}",
                "--userconfig=${userConfig.absolutePathString()}",
                "--globalconfig=${emptyGlobalConfig.absolutePathString()}",
            )
            logger.info("Resolving NPM dependencies against $registryUrl...")
            // the command only carries the path of the user config, never the credentials it may contain
            logger.debug("Running: {}", command.joinToString(" "))
            val result = runProcessAndCaptureOutput(
                workingDir = workspacePath,
                command = command,
            )
            require(result.exitCode == 0) {
                buildString {
                    appendLine("npm install failed with exit code ${result.exitCode}: ${command.joinToString(" ")}")
                    appendLine("stdout:")
                    appendLine(result.stdout)
                    appendLine("stderr:")
                    appendLine(result.stderr)
                }
            }
        } finally {
            userConfig.deleteIfExists()
        }
    }

    /**
     * Materializes the credentials of the registry into an npm user config, the only place npm reads them from.
     *
     * The file is created outside of [workDir]: that directory is the Bazel external repository the manifest is
     * generated into, which is no place for a credential. `createTempFile` restricts it to its owner on POSIX.
     */
    private fun createUserConfig(): Path {
        val userConfig = createTempFile(prefix = "kmp-resolver-npmrc", suffix = ".ini")
        userConfig.writeText(npmAuthConfig(registryUrl, credentials.credentialsFor(registryUrl)))
        return userConfig
    }

    private fun requireSingleVersionPerPackage(artifactsByVariant: Map<MultiplatformLibraryId, List<NpmMultiplatformLibraryArtifact>>) {
        val conflicts =
            artifactsByVariant.entries.flatMap { (variantId, artifacts) -> artifacts.map { variantId to it } }
                .groupBy { (_, artifact) -> artifact.name }.mapValues { (_, requesters) ->
                    requesters.groupBy({ (_, artifact) -> artifact.version }, { (variantId, _) -> variantId })
                }.filterValues { versions -> versions.size > 1 }
        require(conflicts.isEmpty()) {
            buildString {
                appendLine("NPM packages resolved to multiple versions, hardcode a single version with --npm-package-version:")
                conflicts.forEach { (name, versions) ->
                    appendLine("\t[$name] versions:")
                    versions.forEach { (version, variantIds) ->
                        appendLine("\t\t$version required by: ${variantIds.joinToString(", ") { it.gav }}")
                    }
                }
            }
        }
    }

    companion object {
        internal const val DEFAULT_REGISTRY_URL = "https://registry.npmjs.org"
    }
}

/**
 * Renders the npm user config authenticating against [registryUrl] with [credentials], empty when there are none.
 *
 * npm has no way to send an arbitrary header, so the `Authorization` header the caller resolved is translated into
 * the npm config it understands. Credentials that cannot be translated fail the resolution instead of being
 * silently dropped, which would surface much later as an opaque npm 401.
 */
internal fun npmAuthConfig(registryUrl: String, credentials: RepositoryCredentials?): String {
    val headers = credentials?.requestHeaders().orEmpty()
    if (headers.isEmpty()) return ""

    val unsupportedHeaders = headers.keys.filterNot { it.equals(AUTHORIZATION_HEADER, ignoreCase = true) }.sorted()
    if (unsupportedHeaders.isNotEmpty()) {
        throw UnsupportedCredentialsException(
            "npm can only be authenticated with an `$AUTHORIZATION_HEADER` header, but the credentials of " +
                "$registryUrl also declare: ${unsupportedHeaders.joinToString(", ")}",
        )
    }
    val authorization = credentials?.singleAuthorizationValue() ?: throw UnsupportedCredentialsException(
        "npm can only be authenticated with a single `$AUTHORIZATION_HEADER` header, but the credentials of " +
            "$registryUrl declare several",
    )

    val nerfDart = registryUrl.toNpmNerfDart()
    return when {
        authorization.startsWith(BEARER_PREFIX) ->
            "$nerfDart:_authToken=${authorization.removePrefix(BEARER_PREFIX)}\n"

        authorization.startsWith(BASIC_PREFIX) ->
            "$nerfDart:_auth=${authorization.removePrefix(BASIC_PREFIX)}\n"

        else -> throw UnsupportedCredentialsException(
            "npm only supports `Bearer` and `Basic` authorization, but the credentials of $registryUrl declare " +
                "`${authorization.substringBefore(' ')}`",
        )
    }
}

/**
 * The npm "nerf dart" of a registry URL, which is how npm keys the credentials of a registry in its config:
 * the URL without its scheme, always ending with a slash (`https://registry.example.com/npm` becomes
 * `//registry.example.com/npm/`).
 */
internal fun String.toNpmNerfDart(): String = "//" + substringAfter("://", missingDelimiterValue = this)
    .trimEnd('/') + "/"

private fun String.sanitizedAsDirectoryName(): String = map { c ->
    when {
        c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' -> c
        else -> '_'
    }
}.joinToString("")

@Serializable
internal data class NpmPackageLock(
    val lockfileVersion: Int,
    val packages: Map<String, NpmPackageLockEntry> = emptyMap(),
)

@Serializable
internal data class NpmPackageLockEntry(
    val name: String? = null,
    val version: String? = null,
    val resolved: String? = null,
    val integrity: String? = null,
    val link: Boolean = false,
    val optional: Boolean = false,
    val dependencies: Map<String, String> = emptyMap(),
    val peerDependencies: Map<String, String> = emptyMap(),
    val peerDependenciesMeta: Map<String, NpmPeerDependencyMeta> = emptyMap(),
)

@Serializable
internal data class NpmPeerDependencyMeta(
    val optional: Boolean = false,
)

/**
 * Walks the lock graph from the workspace member at [memberPath] and returns the artifacts of its transitive NPM
 * closure, sorted by package name. Workspace members themselves (embedded in klibs) are never emitted as artifacts.
 */
internal fun NpmPackageLock.closureArtifacts(
    memberPath: String, request: NpmResolutionRequest
): List<NpmMultiplatformLibraryArtifact> = transitiveClosure(
    pending = setOf(memberPath),
    visited = emptySet(),
    request = request
).mapNotNull { path -> entryAt(path, request).toNpmArtifactOrNull(path, request) }.sortedBy { it.name }

private tailrec fun NpmPackageLock.transitiveClosure(
    pending: Set<String>, visited: Set<String>, request: NpmResolutionRequest
): Set<String> = when {
    pending.isEmpty() -> visited
    else -> {
        val discovered = pending.flatMap { path -> neighborsOf(path, request) }.toSet() - visited - pending
        transitiveClosure(pending = discovered, visited = visited + pending, request = request)
    }
}

private fun NpmPackageLock.neighborsOf(path: String, request: NpmResolutionRequest): Set<String> {
    val entry = entryAt(path, request)
    return when {
        entry.link -> setOfNotNull(
            entry.resolved
                ?: error("[${request.variantId.gav}] package-lock.json link entry $path has no resolution target"),
        )

        else -> entry.dependencyNames().mapNotNull { dependencyName ->
            when (val dependencyPath = resolveDependencyPath(fromPath = path, dependencyName = dependencyName)) {
                null if entry.isOptionalDependency(dependencyName) -> null
                null -> error("[${request.variantId.gav}] package-lock.json does not resolve dependency $dependencyName of $path")

                else -> dependencyPath
            }
        }.toSet()
    }
}

private fun NpmPackageLock.entryAt(path: String, request: NpmResolutionRequest): NpmPackageLockEntry =
    packages[path] ?: error("[${request.variantId.gav}] package-lock.json has no entry for $path")

private fun NpmPackageLockEntry.dependencyNames(): Set<String> = dependencies.keys + peerDependencies.keys

private fun NpmPackageLockEntry.isOptionalDependency(dependencyName: String): Boolean =
    peerDependenciesMeta[dependencyName]?.optional == true

private fun NpmPackageLock.resolveDependencyPath(fromPath: String, dependencyName: String): String? =
    generateSequence(fromPath) { path ->
        when (val index = path.lastIndexOf("/node_modules/")) {
            -1 -> when {
                path.isEmpty() -> null
                else -> ""
            }

            else -> path.substring(0, index)
        }
    }.map { ancestor ->
        when (ancestor) {
            "" -> "node_modules/$dependencyName"
            else -> "$ancestor/node_modules/$dependencyName"
        }
    }.firstOrNull { candidate -> candidate in packages }

private fun NpmPackageLockEntry.toNpmArtifactOrNull(
    path: String,
    request: NpmResolutionRequest,
): NpmMultiplatformLibraryArtifact? {
    val resolvedUrl = resolved
    return when {
        // workspace members (packages/*) and link entries carry no registry artifact
        link || resolvedUrl == null || !resolvedUrl.startsWith("http") -> null
        else -> NpmMultiplatformLibraryArtifact(
            name = name ?: path.substringAfterLast("node_modules/"),
            version = version ?: error("[${request.variantId.gav}] package-lock.json entry $path has no version"),
            url = resolvedUrl,
            integrity = MultiplatformLibraryArtifactIntegrity.fromBazelIntegrityString(
                (integrity
                    ?: error("[${request.variantId.gav}] package-lock.json entry $path has no integrity")).substringBefore(
                    ' '
                ),
            ),
        )
    }
}
