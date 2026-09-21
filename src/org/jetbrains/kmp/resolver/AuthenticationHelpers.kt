package org.jetbrains.kmp.resolver

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.jetbrains.amper.dependency.resolution.MavenRepository
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.encoding.Base64
import kotlin.io.path.inputStream

internal const val AUTHORIZATION_HEADER = "Authorization"
internal const val BASIC_PREFIX = "Basic "
internal const val BEARER_PREFIX = "Bearer "

/**
 * Credentials of a single repository, resolved by the caller.
 *
 * [username]/[password] carry plain HTTP Basic auth, as a `.netrc` file provides it. [headers] carries arbitrary
 * HTTP headers, which is what a
 * [Bazel credential helper](https://github.com/bazelbuild/proposals/blob/main/designs/2022-06-07-bazel-credential-helpers.md)
 * returns, and is the only way to express anything but Basic auth (a bearer token, typically). When both are
 * specified, [headers] wins.
 */
@Serializable
internal data class RepositoryCredentials(
    val repositoryUrl: String,
    val username: String? = null,
    val password: String? = null,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    /**
     * Headers to attach to every request to this repository, empty when no credentials are configured.
     */
    fun requestHeaders(): Map<String, List<String>> = when {
        headers.isNotEmpty() -> headers
        username != null && password != null -> mapOf(
            AUTHORIZATION_HEADER to listOf(basicAuthorizationValue(username, password)),
        )

        else -> emptyMap()
    }

    /**
     * Credentials expressed as HTTP Basic auth, which is the only authentication Amper can send natively through
     * [MavenRepository]. An `Authorization: Basic` header is decoded back into its username/password so that
     * callers relying on a credential helper returning Basic auth need no header injection at all.
     *
     * `null` when the credentials cannot be expressed as Basic auth, in which case [requestHeaders] has to be
     * attached to the requests by [CredentialAwareHttpClient] instead.
     */
    fun basicAuth(): BasicAuth? {
        if (username != null && password != null) return BasicAuth(username, password)

        val encoded = singleAuthorizationValue()?.takeIf { it.startsWith(BASIC_PREFIX) }
            ?.removePrefix(BASIC_PREFIX)
            ?: return null
        val decoded = runCatching { Base64.decode(encoded).decodeToString() }.getOrNull() ?: return null
        val separator = decoded.indexOf(':')
        return when {
            separator < 0 -> null
            else -> BasicAuth(username = decoded.substring(0, separator), password = decoded.substring(separator + 1))
        }
    }

    /**
     * The single `Authorization` header value these credentials send, or `null` when they send none or several.
     */
    fun singleAuthorizationValue(): String? = requestHeaders()
        .filterKeys { it.equals(AUTHORIZATION_HEADER, ignoreCase = true) }
        .values
        .singleOrNull()
        ?.singleOrNull()

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
        }

        @OptIn(ExperimentalSerializationApi::class)
        fun fromStream(inputStream: InputStream): Map<String, RepositoryCredentials> = inputStream.use {
            json.decodeFromStream(ListSerializer(serializer()), inputStream).associateBy { it.repositoryUrl }
        }

        fun fromFile(path: Path): Map<String, RepositoryCredentials> = path.inputStream().use { fromStream(it) }
    }
}

internal data class BasicAuth(val username: String, val password: String)

private fun basicAuthorizationValue(username: String, password: String): String =
    BASIC_PREFIX + Base64.encode("$username:$password".encodeToByteArray())

internal fun List<String>.withRepositoryCredentials(credentialsByRepositoryUrl: Map<String, RepositoryCredentials>): List<MavenRepository> =
    map { repository ->
        val basicAuth = credentialsByRepositoryUrl[repository]?.basicAuth()
        MavenRepository(
            url = repository,
            userName = basicAuth?.username,
            password = basicAuth?.password,
        )
    }

/**
 * Resolves the credentials to use for an arbitrary artifact URL, by matching it against the repository URLs the
 * caller provided credentials for.
 *
 * The longest matching repository URL wins, so that credentials scoped to a single repository of a host take
 * precedence over credentials scoped to another repository sharing a shorter prefix on the same host.
 */
internal class RepositoryCredentialsResolver(credentials: Collection<RepositoryCredentials>) {
    private val byDescendingUrlLength = credentials
        .filter { it.requestHeaders().isNotEmpty() }
        .sortedByDescending { it.repositoryUrl.trimEnd('/').length }

    /**
     * Whether some credentials cannot be expressed as HTTP Basic auth, and therefore have to be attached to the
     * requests of the Amper resolution by a [CredentialAwareHttpClient]. Amper sends Basic auth natively, so a
     * resolution that only needs Basic auth is left completely untouched.
     */
    val requiresHeaderInjection: Boolean = byDescendingUrlLength.any { it.basicAuth() == null }

    fun credentialsFor(url: String): RepositoryCredentials? =
        byDescendingUrlLength.firstOrNull { url.isWithinRepository(it.repositoryUrl) }

    fun headersFor(url: String): Map<String, List<String>> = credentialsFor(url)?.requestHeaders().orEmpty()

    private fun String.isWithinRepository(repositoryUrl: String): Boolean {
        val base = repositoryUrl.trimEnd('/')
        return this == base || startsWith("$base/")
    }
}
