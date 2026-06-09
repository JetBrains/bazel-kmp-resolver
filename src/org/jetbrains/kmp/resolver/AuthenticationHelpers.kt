package org.jetbrains.kmp.resolver

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.jetbrains.amper.dependency.resolution.MavenRepository
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.inputStream

@Serializable
internal data class RepositoryCredentials(
    val repositoryUrl: String,
    val username: String,
    val password: String,
) {
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

internal fun List<String>.withRepositoryCredentials(credentialsByRepositoryUrl: Map<String, RepositoryCredentials>): List<MavenRepository> =
    map { repository ->
        val credentials = credentialsByRepositoryUrl[repository]
        MavenRepository(
            url = repository,
            userName = credentials?.username.orEmpty(),
            password = credentials?.password.orEmpty(),
        )
    }
