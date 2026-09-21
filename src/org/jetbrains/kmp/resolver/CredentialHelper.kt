package org.jetbrains.kmp.resolver

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.processes.runProcessAndCaptureOutput
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.time.Duration

/**
 * Support for [Bazel credential helpers](https://github.com/bazelbuild/proposals/blob/main/designs/2022-06-07-bazel-credential-helpers.md).
 *
 * Bazel authenticates the downloads it performs itself by running a helper program, but a repository rule cannot
 * do the same: Bazel exposes no credential helper API to Starlark, and `repository_ctx.execute` cannot write to
 * the standard input of the process it spawns, which the protocol requires. So the caller forwards the helper
 * configuration to this resolver, verbatim from its `.bazelrc`, and the resolver speaks the protocol itself for
 * the requests it performs while resolving the dependency graph.
 */

/**
 * Hosts a credential helper is configured for, mirroring the patterns Bazel's `--credential_helper` accepts.
 */
internal sealed interface CredentialHelperScope {
    fun matches(host: String): Boolean

    /**
     * How specific this scope is. Bazel picks the most specific match, an exact DNS name winning over a wildcard,
     * the longest wildcard winning over a shorter one, and both winning over an unscoped helper.
     */
    val specificity: Int

    data class Exact(val host: String) : CredentialHelperScope {
        override fun matches(host: String): Boolean = host == this.host
        override val specificity: Int get() = Int.MAX_VALUE
    }

    /** `*.example.com` matches `example.com` itself and all of its subdomains. */
    data class Subdomains(val domain: String) : CredentialHelperScope {
        override fun matches(host: String): Boolean = host == domain || host.endsWith(".$domain")
        override val specificity: Int get() = domain.length
    }

    /** An unscoped helper, used for every host no other helper matches. */
    data object AnyHost : CredentialHelperScope {
        override fun matches(host: String): Boolean = true
        override val specificity: Int get() = -1
    }
}

internal data class CredentialHelper(val scope: CredentialHelperScope, val path: String)

/**
 * Parses a `[<pattern>=]<path>` value, using the syntax of Bazel's `--credential_helper` so that the values of a
 * `.bazelrc` can be forwarded verbatim.
 *
 * As Bazel does, the pattern is separated from the path by the left-most `=`, and a value without one configures
 * the helper for every host.
 */
internal fun parseCredentialHelper(value: String): CredentialHelper {
    val separator = value.indexOf('=')
    val pattern = when (separator) {
        -1 -> ""
        else -> value.substring(0, separator)
    }
    val path = when (separator) {
        -1 -> value
        else -> value.substring(separator + 1)
    }
    require(path.isNotEmpty()) { "Credential helper '$value' declares no path" }

    val scope = when {
        pattern.isEmpty() -> CredentialHelperScope.AnyHost
        pattern.startsWith("*.") -> CredentialHelperScope.Subdomains(pattern.removePrefix("*."))
        else -> CredentialHelperScope.Exact(pattern)
    }
    return CredentialHelper(scope = scope, path = path)
}

/**
 * Resolves the path of a credential helper the way Bazel does: `%workspace%` is expanded against
 * [workspaceDirectory], a value carrying no path separator is looked up on `PATH`, and anything else is taken as
 * a path, relative to [workspaceDirectory] when not absolute.
 */
internal fun resolveCredentialHelperPath(path: String, workspaceDirectory: Path): Path {
    if (path.startsWith(WORKSPACE_PLACEHOLDER)) {
        return workspaceDirectory.resolve(path.removePrefix(WORKSPACE_PLACEHOLDER).trimStart('/', '\\'))
    }
    if (path.contains('/') || path.contains('\\')) {
        return workspaceDirectory.resolve(path).normalize()
    }
    return lookUpOnPath(path)
        ?: throw IllegalArgumentException("Credential helper '$path' was not found on PATH")
}

private const val WORKSPACE_PLACEHOLDER = "%workspace%"

private fun lookUpOnPath(name: String): Path? {
    val extensions = when {
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true) ->
            listOf("", ".exe", ".cmd", ".bat")

        else -> listOf("")
    }
    return System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .filter { it.isNotEmpty() }
        .firstNotNullOfOrNull { directory ->
            extensions.map { extension -> Path.of(directory, name + extension) }
                .firstOrNull { it.isRegularFile() && it.isExecutable() }
        }
}

@Serializable
private data class CredentialHelperRequest(val uri: String)

@Serializable
private data class CredentialHelperResponse(val headers: Map<String, List<String>> = emptyMap())

/**
 * Asks the configured credential helpers for the credentials of the repositories this resolution uses.
 *
 * Credentials are resolved once per repository rather than once per artifact URL. A helper is a subprocess and a
 * resolution issues thousands of requests, and repository credentials do not vary per artifact in practice, so
 * this is the caching the protocol explicitly allows ("Bazel may cache credentials to avoid running the
 * credential helper binary too often"). The consequence is that `expires` is not acted upon: a resolution
 * outliving its token fails rather than transparently renewing it.
 */
internal class CredentialHelperProvider(
    helpers: List<CredentialHelper>,
    private val workspaceDirectory: Path,
    private val timeout: Duration,
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    // as in Bazel, a later entry overrides an earlier one declaring the same pattern
    private val pathsByScope: Map<CredentialHelperScope, String> = helpers.associate { it.scope to it.path }

    /**
     * The helper configured for [host], the most specific match winning, or `null` when none matches. As in
     * Bazel, there is no implicit default: an unmatched host is simply queried without credentials.
     */
    internal fun helperFor(host: String): String? = pathsByScope.entries
        .filter { (scope, _) -> scope.matches(host) }
        .maxByOrNull { (scope, _) -> scope.specificity }
        ?.value

    suspend fun credentialsFor(repositoryUrls: Collection<String>): Map<String, RepositoryCredentials> =
        repositoryUrls.distinct().mapNotNull { url -> credentialsFor(url) }.associateBy { it.repositoryUrl }

    private suspend fun credentialsFor(repositoryUrl: String): RepositoryCredentials? {
        val host = URI.create(repositoryUrl).host
            ?: throw IllegalArgumentException("Repository URL '$repositoryUrl' has no host")
        val helper = helperFor(host) ?: return null

        val headers = invoke(resolveCredentialHelperPath(helper, workspaceDirectory), repositoryUrl)
        // a valid response with no header means "no credentials needed", which is not an error
        return when {
            headers.isEmpty() -> null
            else -> RepositoryCredentials(repositoryUrl = repositoryUrl, headers = headers)
        }
    }

    private suspend fun invoke(helperPath: Path, uri: String): Map<String, List<String>> {
        logger.info("[$uri] asking credential helper ${helperPath.absolutePathString()}...")
        val command = listOf(helperPath.absolutePathString(), "get")
        val result = withTimeout(timeout) {
            runProcessAndCaptureOutput(
                workingDir = workspaceDirectory,
                command = command,
                // the protocol passes the request on the standard input of the helper
                input = ProcessInput.Text(json.encodeToString(CredentialHelperRequest(uri))),
            )
        }
        require(result.exitCode == 0) {
            buildString {
                appendLine("Credential helper ${helperPath.absolutePathString()} failed for $uri with exit code ${result.exitCode}")
                appendLine("stderr:")
                appendLine(result.stderr)
            }
        }

        // the response is never logged, it carries the credentials themselves
        val response = runCatching { json.decodeFromString<CredentialHelperResponse>(result.stdout) }.getOrElse {
            throw IllegalStateException(
                "Credential helper ${helperPath.absolutePathString()} did not return a valid response for $uri: ${it.message}",
                it,
            )
        }
        logger.debug("[$uri] credential helper returned headers: {}", response.headers.keys.sorted())
        return response.headers
    }

    companion object {
        private val json = Json {
            // notably `expires`, which is not acted upon: credentials are resolved once, before the resolution
            ignoreUnknownKeys = true
        }
    }
}
