package org.jetbrains.kmp.resolver.auth

import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.processes.runProcessAndCaptureOutput
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Host pattern restricting a credential helper to some URIs, as Bazel's
 * `--credential_helper=<scope>=<path>`: a domain name, or `*.` followed by a domain name to match
 * that domain and all of its subdomains.
 */
internal data class CredentialHelperScope(val host: String, val includesSubdomains: Boolean) {
    fun matches(host: String): Boolean {
        val candidate = host.lowercase()
        return candidate == this.host || (includesSubdomains && candidate.endsWith(".${this.host}"))
    }
}

/**
 * One `--credential-helper` entry, in Bazel's `[scope=]path` syntax. Mirrors Bazel's
 * `CredentialHelperOptionConverter`: the value is split at the leftmost `=`, both parts must be
 * non-empty. The path is absolute, or a bare executable name looked up on `PATH` when the process
 * starts. `%workspace%` is not expanded here, the caller does it.
 */
internal data class CredentialHelperOption(val scope: CredentialHelperScope?, val path: String) {
    companion object {
        fun parse(value: String): CredentialHelperOption {
            val parts = value.split("=", limit = 2)
            return when (parts.size) {
                1 -> CredentialHelperOption(scope = null, path = parsePath(value))
                else -> {
                    val (scope, path) = parts
                    CredentialHelperOption(scope = parseScope(scope), path = parsePath(path))
                }
            }
        }

        private fun parsePath(path: String): String {
            require(path.isNotEmpty()) { "Credential helper path must not be empty" }
            return path
        }

        private fun parseScope(scope: String): CredentialHelperScope {
            require(scope.isNotEmpty()) { "Credential helper scope must not be empty" }
            val includesSubdomains = scope.startsWith("*.") && scope.length > 2
            val host = if (includesSubdomains) scope.substring(2) else scope
            require(host.isNotEmpty() && '*' !in host && '/' !in host) {
                "Credential helper scope must be a valid domain name with an optional leading '*.' wildcard, got '$scope'"
            }
            return CredentialHelperScope(
                host = host.lowercase(),
                includesSubdomains = includesSubdomains,
            )
        }
    }
}

/**
 * Picks the credential helper applying to a URI with Bazel's rules: an exact host scope beats a
 * wildcard one, a longer wildcard suffix beats a shorter one, an unscoped helper applies to
 * everything else; among helpers of the same scope, the last declared wins. Without a matching
 * helper, no credentials are requested at all.
 */
internal class CredentialHelperProvider(private val options: List<CredentialHelperOption>) {
    fun find(uri: URI): CredentialHelperOption? =
        when (val host = uri.host) {
            null -> null
            else ->
                options
                    .withIndex()
                    .filter { (_, option) -> option.scope?.matches(host) ?: true }
                    .maxWithOrNull(
                        compareBy(
                            { (_, option) -> option.scope.specificity() },
                            { (_, option) -> option.scope?.host?.length ?: 0 },
                            { (index, _) -> index },
                        ),
                    )
                    ?.value
        }

    private fun CredentialHelperScope?.specificity(): Int =
        when {
            this == null -> 0
            includesSubdomains -> 1
            else -> 2
        }
}

internal class CredentialHelperException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Requests HTTP headers from Bazel credential helpers, speaking the credential helper protocol:
 * `<helper> get` with `{"uri": "..."}` on stdin, answering `{"headers": {"Name": ["value", ...]},
 * "expires": "<RFC 3339>"}` on stdout.
 *
 * Responses are cached per URI like Bazel does, until the `expires` timestamp of the response or,
 * without one, for [cacheDuration]. Concurrent requests for the same URI share a single helper
 * invocation. A URI no helper applies to gets null, so that other [RequestCredentials] may
 * authenticate it. Failures are not cached: a helper exiting with a non-zero code, timing out after
 * [timeout], or printing something else than the protocol fails the request with a
 * [CredentialHelperException], as Bazel fails the build.
 *
 * Invocations run as children of [scope], not of the requesting coroutine, so that a caller giving
 * up on a request does not cancel the invocation other callers of the same URI wait for; cancelling
 * [scope] kills the running helpers.
 */
internal class CredentialHelperCredentials(
    private val scope: CoroutineScope,
    private val provider: CredentialHelperProvider,
    private val timeout: Duration,
    private val cacheDuration: Duration,
    private val clock: Clock,
    private val workingDir: Path?,
) : RequestCredentials {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private class Entry(val headers: Map<String, List<String>>, val expiresAt: Instant)

    private val cache = ConcurrentHashMap<URI, Deferred<Result<Entry>>>()

    override suspend fun authenticationFor(uri: URI): Authentication =
        when (val helper = provider.find(uri)) {
            null -> Authentication.NotApplicable
            else -> {
                val deferred = invocation(uri, helper)
                val entry = deferred.awaitOrEvict(uri)
                when {
                    entry.expiresAt > clock.now() -> Authentication.Headers(entry.headers)
                    else -> {
                        // The entry expired: evict that very deferred, so that an invocation
                        // another caller started meanwhile is joined rather than discarded, and
                        // serve this request from the fresh entry even if the helper hands out
                        // already expired credentials again (Bazel keeps such entries for zero
                        // time too).
                        cache.remove(uri, deferred)
                        Authentication.Headers(invocation(uri, helper).awaitOrEvict(uri).headers)
                    }
                }
            }
        }

    /**
     * The invocation serving [uri], shared by its concurrent callers. Its failure is a value handed
     * to the awaiters, not a failure of [scope].
     */
    private fun invocation(uri: URI, helper: CredentialHelperOption): Deferred<Result<Entry>> =
        cache.computeIfAbsent(uri) {
            scope.async {
                try {
                    Result.success(invoke(helper, uri))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }

    /**
     * Awaits this invocation; a failed one is evicted so that the next request invokes the helper
     * again, a still running one that this caller merely stops waiting for is left to the other
     * callers.
     */
    private suspend fun Deferred<Result<Entry>>.awaitOrEvict(uri: URI): Entry =
        await().getOrElse { failure ->
            cache.remove(uri, this)
            throw failure
        }

    private suspend fun invoke(helper: CredentialHelperOption, uri: URI): Entry {
        logger.debug("Invoking credential helper {} for {}", helper.path, uri)
        val result =
            try {
                withTimeout(timeout) {
                    runProcessAndCaptureOutput(
                        workingDir = workingDir,
                        command = listOf(helper.path, "get"),
                        input =
                            ProcessInput.Text(
                                json.encodeToString(
                                    GetCredentialsRequest.serializer(),
                                    GetCredentialsRequest(uri = uri.toString()),
                                ),
                            ),
                    )
                }
            } catch (e: TimeoutCancellationException) {
                throw CredentialHelperException(
                    "Credential helper ${helper.path} did not answer within $timeout for $uri",
                    e,
                )
            } catch (e: IOException) {
                throw CredentialHelperException(
                    "Credential helper ${helper.path} could not be run for $uri: ${e.message}",
                    e,
                )
            }
        if (result.exitCode != 0) {
            throw CredentialHelperException(
                buildString {
                    appendLine(
                        "Credential helper ${helper.path} failed with exit code ${result.exitCode} for $uri",
                    )
                    appendLine("stderr:")
                    append(result.stderr)
                },
            )
        }
        val response =
            try {
                json.decodeFromString(GetCredentialsResponse.serializer(), result.stdout)
            } catch (e: SerializationException) {
                throw CredentialHelperException(
                    "Credential helper ${helper.path} printed an invalid response for $uri: ${e.message}\nstdout:\n${result.stdout}",
                    e,
                )
            } catch (e: IllegalArgumentException) {
                throw CredentialHelperException(
                    "Credential helper ${helper.path} printed an invalid response for $uri: ${e.message}\nstdout:\n${result.stdout}",
                    e,
                )
            }
        val expiresAt =
            response.expires
                ?.takeIf { it.isNotEmpty() }
                ?.let { expires ->
                    try {
                        Instant.parse(expires)
                    } catch (e: IllegalArgumentException) {
                        throw CredentialHelperException(
                            "Credential helper ${helper.path} printed an 'expires' value that is not an RFC 3339 timestamp for $uri: '$expires'",
                            e,
                        )
                    }
                }
        return Entry(
            headers = response.headers ?: emptyMap(),
            expiresAt = expiresAt ?: (clock.now() + cacheDuration),
        )
    }

    @Serializable private data class GetCredentialsRequest(val uri: String)

    @Serializable
    private data class GetCredentialsResponse(
        val headers: Map<String, List<String>>? = null,
        val expires: String? = null,
    )

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
        }
    }
}

/**
 * Runs [block] with [CredentialHelperCredentials] whose helper invocations run in the scope of this
 * call.
 */
internal suspend fun <T> withCredentialHelperCredentials(
    provider: CredentialHelperProvider,
    timeout: Duration,
    cacheDuration: Duration,
    clock: Clock,
    workingDir: Path?,
    block: suspend (CredentialHelperCredentials) -> T,
): T = coroutineScope {
    block(
        CredentialHelperCredentials(
            scope = this,
            provider = provider,
            timeout = timeout,
            cacheDuration = cacheDuration,
            clock = clock,
            workingDir = workingDir,
        ),
    )
}
