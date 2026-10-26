package org.jetbrains.kmp.resolver.auth

import java.io.InputStream
import java.net.URI
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.inputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

@Serializable
internal data class RepositoryCredentials(
    val repositoryUrl: String,
    val username: String?,
    val password: String?,
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
        }

        @OptIn(ExperimentalSerializationApi::class)
        fun fromStream(inputStream: InputStream): List<RepositoryCredentials> = inputStream.use {
            json.decodeFromStream(ListSerializer(serializer()), inputStream)
        }

        fun fromFile(path: Path): List<RepositoryCredentials> =
            path.inputStream().use { fromStream(it) }
    }
}

/**
 * Basic authentication of the requests under a repository URL with the username and password
 * declared for that repository, the longest matching repository URL winning. Entries missing either
 * apply to nothing.
 */
internal class BasicRepositoryCredentials(credentials: List<RepositoryCredentials>) :
    RequestCredentials {
    private val authorizationByRepositoryPrefix: Map<String, String> =
        credentials
            .filter { !it.username.isNullOrBlank() && !it.password.isNullOrBlank() }
            .associate {
                "${it.repositoryUrl.trimEnd('/')}/" to
                    basicAuthorization(it.username!!, it.password!!)
            }

    override suspend fun headersFor(uri: URI): Map<String, List<String>>? {
        val url = uri.toString()
        val authorization =
            authorizationByRepositoryPrefix.entries
                .filter { (prefix, _) -> url.startsWith(prefix) }
                .maxByOrNull { (prefix, _) -> prefix.length }
                ?.value
        return when (authorization) {
            null -> null
            else -> mapOf("Authorization" to listOf(authorization))
        }
    }

    private fun basicAuthorization(username: String, password: String): String =
        "Basic ${Base64.getEncoder().encodeToString("$username:$password".toByteArray())}"
}
