package org.jetbrains.kmp.resolver

import com.sun.net.httpserver.HttpServer
import java.net.http.HttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class ArtifactUrlResolverTest {
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val authorizations = ConcurrentHashMap<String, List<String>>()

    /**
     * `/ok` exists, `/missing` does not, `/flaky` fails twice before existing, `/down` always
     * fails, `/throttled` fails once asking to retry after a second.
     */
    private val server: HttpServer = startLocalHttpServer { exchange ->
        val path = exchange.requestURI.path
        val hit = hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
        authorizations[path] = exchange.requestHeaders["Authorization"] ?: emptyList()
        when (path) {
            "/ok" -> exchange.respond(200, ByteArray(0))
            "/missing" -> exchange.respond(404, ByteArray(0))
            "/flaky" -> exchange.respond(if (hit <= 2) 503 else 200, ByteArray(0))
            "/throttled" -> {
                if (hit == 1) exchange.responseHeaders.add("Retry-After", "1")
                exchange.respond(if (hit == 1) 503 else 200, ByteArray(0))
            }
            else -> exchange.respond(500, ByteArray(0))
        }
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private val fastRetries =
        RetryPolicy(
            maxRetries = 5,
            baseDelay = 10.milliseconds,
            maxDelay = 50.milliseconds,
            randomization = Duration.ZERO,
        )

    private fun resolver(httpClient: HttpClient) =
        ArtifactUrlResolver(
            httpClient = httpClient,
            allowedConcurrentConnections = 4,
            requestTimeout = 5.seconds,
            retryPolicy = fastRetries,
        )

    private val noCredentials =
        RepositoryCredentials(repositoryUrl = "unused", username = null, password = null)

    @Test
    fun `existing and missing artifacts`() = runBlocking {
        jdkHttpClient(connectTimeout = 5.seconds).use { httpClient ->
            val resolver = resolver(httpClient)

            assertEquals(
                ArtifactFile.Resolved(server.urlOf("/ok")),
                resolver.artifactExistsAt(server.urlOf("/ok"), noCredentials),
            )
            assertEquals(
                ArtifactFile.NotFound,
                resolver.artifactExistsAt(server.urlOf("/missing"), noCredentials),
            )
        }
        assertEquals(emptyList(), authorizations["/ok"])
    }

    @Test
    fun `results are cached per URL`() = runBlocking {
        jdkHttpClient(connectTimeout = 5.seconds).use { httpClient ->
            val resolver = resolver(httpClient)

            repeat(3) { resolver.artifactExistsAt(server.urlOf("/ok"), noCredentials) }
        }
        assertEquals(1, hits["/ok"]?.get())
    }

    @Test
    fun `server errors are retried`() = runBlocking {
        jdkHttpClient(connectTimeout = 5.seconds).use { httpClient ->
            val resolver = resolver(httpClient)

            assertEquals(
                ArtifactFile.Resolved(server.urlOf("/flaky")),
                resolver.artifactExistsAt(server.urlOf("/flaky"), noCredentials),
            )
            assertEquals(
                ArtifactFile.NotFound,
                resolver.artifactExistsAt(server.urlOf("/down"), noCredentials),
            )
            assertEquals(
                ArtifactFile.Resolved(server.urlOf("/throttled")),
                resolver.artifactExistsAt(server.urlOf("/throttled"), noCredentials),
            )
        }
        assertEquals(3, hits["/flaky"]?.get())
        assertEquals(6, hits["/down"]?.get())
        assertEquals(2, hits["/throttled"]?.get())
    }

    @Test
    fun `repository credentials are sent as basic authentication`() = runBlocking {
        jdkHttpClient(connectTimeout = 5.seconds).use { httpClient ->
            resolver(httpClient)
                .artifactExistsAt(
                    server.urlOf("/ok"),
                    RepositoryCredentials(
                        repositoryUrl = "unused",
                        username = "alice",
                        password = "s3cret",
                    ),
                )
        }
        assertEquals(listOf("Basic YWxpY2U6czNjcmV0"), authorizations["/ok"])
    }

    @Test
    fun `retry delays grow exponentially up to the maximum and honour Retry-After`() {
        val policy =
            RetryPolicy(
                maxRetries = 5,
                baseDelay = 3.seconds,
                maxDelay = 60.seconds,
                randomization = Duration.ZERO,
            )

        assertEquals(3.seconds, policy.delayBefore(retry = 1, retryAfter = null))
        assertEquals(6.seconds, policy.delayBefore(retry = 2, retryAfter = null))
        assertEquals(48.seconds, policy.delayBefore(retry = 5, retryAfter = null))
        assertEquals(60.seconds, policy.delayBefore(retry = 6, retryAfter = null))
        assertEquals(10.seconds, policy.delayBefore(retry = 1, retryAfter = 10.seconds))
        assertEquals(6.seconds, policy.delayBefore(retry = 2, retryAfter = 1.seconds))
    }
}
