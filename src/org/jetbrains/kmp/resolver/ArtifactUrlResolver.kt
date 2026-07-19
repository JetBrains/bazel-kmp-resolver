package org.jetbrains.kmp.resolver

import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

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

    private val hostSemaphores = ConcurrentHashMap<String, Semaphore>()

    private fun hostSemaphore(artifactUrl: String): Semaphore =
        hostSemaphores.computeIfAbsent(Url(artifactUrl).hostWithPort) {
            Semaphore(allowedConcurrentConnections)
        }

    private val httpClient = HttpClient(Java) {
        followRedirects = true
        expectSuccess = false
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 5)
            retryOnException(maxRetries = 5, retryOnTimeout = true)
            exponentialDelay(baseDelayMs = 3000)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeout.inWholeMilliseconds
            connectTimeoutMillis = connectTimeout.inWholeMilliseconds
        }
        defaultRequest {
            header("User-Agent", "JetBrainsBazelKmpResolver/1.0")
        }
    }
    private val availabilityByUrl = ConcurrentHashMap<String, ArtifactFile>()

    suspend fun artifactExistsAt(artifactUrl: String, credentials: RepositoryCredentials): ArtifactFile {
        return availabilityByUrl.getOrPut(artifactUrl) {
            logger.debug("[$artifactUrl] checking for existence of artifact...")
            val resolved = hostSemaphore(artifactUrl).withPermit {
                httpClient.head {
                    url(artifactUrl)
                    when {
                        credentials.username != null && credentials.password != null -> basicAuth(
                            credentials.username,
                            credentials.password,
                        )

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
