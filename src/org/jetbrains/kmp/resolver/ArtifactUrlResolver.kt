package org.jetbrains.kmp.resolver

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.amper.dependency.resolution.MavenRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

internal data class UnresolvedMultiplatformLibraryArtifact(
    val sha256checksum: String?,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val artifactPath: String,
)

internal data class NodeWithUnresolvedArtifacts(
    val id: MultiplatformLibraryId,
    val variantId: MultiplatformLibraryId,
    val klib: UnresolvedMultiplatformLibraryArtifact,
    val sourceJar: UnresolvedMultiplatformLibraryArtifact?,
    val dependencies: List<MultiplatformLibraryId>,
    val exportedDependencies: List<MultiplatformLibraryId>,
)

internal suspend fun UnresolvedMultiplatformLibraryArtifact.resolve(
    repositories: List<MavenRepository>,
    artifactUrlResolver: ArtifactUrlResolver,
    /**
     * Whether to query all repository for the artifact, or to stop at the first match.
     *
     * Stopping avoids bursting repositories which sometimes time out under the load (Space Packages especially).
     * Checking all repositories is a more reliable way to resolve artifacts in the system using the manifest (e.g. Bazel).
     */
    stopAtFirstRepositoryMatch: Boolean,
): MultiplatformLibraryArtifact {
    val urls = when {
        !stopAtFirstRepositoryMatch -> coroutineScope {
            repositories.map {
                async {
                    artifactUrlResolver.artifactExistsAt(it, artifactPath)
                }
            }.awaitAll().filterIsInstance<ArtifactFile.Resolved>().map { it.url }
        }

        else -> repositories.firstNotNullOfOrNull { // TODO: when trying to support all URLs, we're reaching a lot of timeouts
            when (val artifact = artifactUrlResolver.artifactExistsAt(it, artifactPath)) {
                is ArtifactFile.Resolved -> artifact.url
                else -> null
            }
        }.let { listOfNotNull(it) }
    }
    require(urls.isNotEmpty()) { "No URLs found for artifact $artifactPath" }

    return MultiplatformLibraryArtifact(
        sha256checksum = sha256checksum,
        groupId = groupId,
        artifactId = artifactId,
        version = version,
        urls = urls,
    )
}

internal suspend fun NodeWithUnresolvedArtifacts.resolve(
    repositories: List<MavenRepository>,
    artifactUrlResolver: ArtifactUrlResolver,
    /**
     * Whether to query all repository for the artifact, or to stop at the first match.
     *
     * Stopping avoids bursting repositories which sometimes time out under the load (Space Packages especially).
     * Checking all repositories is a more reliable way to resolve artifacts in the system using the manifest (e.g. Bazel).
     */
    stopAtFirstRepositoryMatch: Boolean,
): MultiplatformLibrary = coroutineScope {
    val resolvedKlib = async { klib.resolve(repositories, artifactUrlResolver, stopAtFirstRepositoryMatch) }
    val resolvedSourceJar =
        sourceJar?.let { async { it.resolve(repositories, artifactUrlResolver, stopAtFirstRepositoryMatch) } }

    MultiplatformLibrary(
        id = id,
        variantId = variantId,
        klib = resolvedKlib.await(),
        sourceJar = resolvedSourceJar?.await(),
        dependencies = dependencies,
        exportedDependencies = exportedDependencies,
    )
}

internal sealed class ArtifactFile {
    data class Resolved(val url: String) : ArtifactFile()
    data object NotFound : ArtifactFile()
}

internal class ArtifactUrlResolver : AutoCloseable {
    val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val httpClient = HttpClient(CIO) {
        followRedirects = true
        expectSuccess = false
        install(HttpRequestRetry) {
            retryOnExceptionOrServerErrors(maxRetries = 20)
            exponentialDelay(baseDelayMs = 3000)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 5000
            connectTimeoutMillis = 5000
        }
    }
    private val availabilityByUrl = ConcurrentHashMap<String, ArtifactFile>()

    suspend fun artifactExistsAt(repository: MavenRepository, artifactPath: String): ArtifactFile {
        val artifactUrl = "${repository.url.trimEnd('/')}/$artifactPath"
        return availabilityByUrl.getOrPut(artifactUrl) {
            logger.info("[$artifactUrl] checking for existence of artifact...")
            val resolved = httpClient.head {
                url(artifactUrl)
                val username = repository.userName
                val password = repository.password
                when {
                    username != null && password != null -> basicAuth(username, password)
                    else -> {}
                }
            }.status.isSuccess()
            when {
                resolved -> {
                    logger.info("[$artifactUrl] found")
                    ArtifactFile.Resolved(artifactUrl)
                }

                else -> {
                    logger.info("[$artifactUrl] not found")
                    ArtifactFile.NotFound
                }
            }
        }
    }

    override fun close() {
        httpClient.close()
    }
}
