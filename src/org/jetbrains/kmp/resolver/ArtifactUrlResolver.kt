package org.jetbrains.kmp.resolver

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal sealed class ArtifactFile {
    data class Resolved(val url: String) : ArtifactFile()

    data object NotFound : ArtifactFile()
}

/**
 * Checks which candidate URLs of artifacts exist, with HEAD requests sent through [httpClient], at
 * most [allowedConcurrentConnections] at a time per host, each answered within [requestTimeout],
 * and retried according to [retryPolicy]. Results are cached per URL.
 */
internal class ArtifactUrlResolver(
    private val httpClient: HttpClient,
    private val allowedConcurrentConnections: Int,
    private val requestTimeout: Duration,
    private val retryPolicy: RetryPolicy,
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    init {
        require(allowedConcurrentConnections > 0) {
            "allowedConcurrentConnections must be greater than zero"
        }
    }

    private val hostSemaphores = ConcurrentHashMap<String, Semaphore>()

    private fun hostSemaphore(uri: URI): Semaphore =
        hostSemaphores.computeIfAbsent("${uri.host}:${uri.port}") {
            Semaphore(allowedConcurrentConnections)
        }

    private val availabilityByUrl = ConcurrentHashMap<String, ArtifactFile>()

    suspend fun artifactExistsAt(
        artifactUrl: String,
        credentials: RepositoryCredentials,
    ): ArtifactFile {
        return availabilityByUrl.getOrPut(artifactUrl) {
            logger.debug("[$artifactUrl] checking for existence of artifact...")
            val uri = URI(artifactUrl)
            val request =
                HttpRequest.newBuilder(uri)
                    .HEAD()
                    .timeout(requestTimeout.toJavaDuration())
                    .header("User-Agent", USER_AGENT)
                    .apply {
                        if (credentials.username != null && credentials.password != null) {
                            header(
                                "Authorization",
                                basicAuthorization(credentials.username, credentials.password),
                            )
                        }
                    }
                    .build()
            val resolved =
                hostSemaphore(uri).withPermit {
                    httpClient
                        .sendWithRetry(request, BodyHandlers.discarding(), retryPolicy)
                        .statusCode() in 200..299
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

    companion object {
        private const val USER_AGENT = "JetBrainsBazelKmpResolver/1.0"

        private fun basicAuthorization(username: String, password: String): String =
            "Basic ${Base64.getEncoder().encodeToString("$username:$password".toByteArray())}"
    }
}
