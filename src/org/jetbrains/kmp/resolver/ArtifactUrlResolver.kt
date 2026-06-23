package org.jetbrains.kmp.resolver

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.amper.dependency.resolution.MavenRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
): MultiplatformLibraryArtifact {
    val urls = coroutineScope {
        repositories.map {
            async { artifactUrlResolver.artifactExistsAt(it, artifactPath) }
        }.awaitAll().filterIsInstance<ArtifactFile.Resolved>().map { it.url }
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
): MultiplatformLibrary = coroutineScope {
    val resolvedKlib = async { klib.resolve(repositories, artifactUrlResolver) }
    val resolvedSourceJar =
        sourceJar?.let { async { it.resolve(repositories, artifactUrlResolver) } }

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

internal class ArtifactUrlResolver(
    private val allowedConcurrentConnections: Int,
    private val requestTimeout: Duration,
    private val connectTimeout: Duration,
) : AutoCloseable {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    init {
        require(allowedConcurrentConnections > 0) {
            "allowedConcurrentConnections must be greater than zero"
        }
    }

    private val connectionSemaphore = Semaphore(allowedConcurrentConnections)

    private val httpClient = HttpClient(CIO) {
        followRedirects = true
        expectSuccess = false
        install(HttpRequestRetry) {
            retryOnExceptionOrServerErrors(maxRetries = 5)
            exponentialDelay(baseDelayMs = 3000)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeout.inWholeMilliseconds
            connectTimeoutMillis = connectTimeout.inWholeMilliseconds
        }
        engine {
            maxConnectionsCount = allowedConcurrentConnections
        }
        defaultRequest {
            header("User-Agent", "JetBrainsBazelKmpResolver/1.0")
        }
    }
    private val availabilityByUrl = ConcurrentHashMap<String, ArtifactFile>()

    suspend fun artifactExistsAt(repository: MavenRepository, artifactPath: String): ArtifactFile {
        val artifactUrl = "${repository.url.trimEnd('/')}/$artifactPath"
        return availabilityByUrl.getOrPut(artifactUrl) {
            logger.debug("[$artifactUrl] checking for existence of artifact...")
            val resolved = connectionSemaphore.withPermit {
                httpClient.head {
                    url(artifactUrl)
                    val username = repository.userName
                    val password = repository.password
                    when {
                        username != null && password != null -> basicAuth(username, password)
                        else -> {}
                    }
                }.status.isSuccess()
            }
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
