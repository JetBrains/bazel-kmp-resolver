package org.jetbrains.kmp.resolver

import com.sun.net.httpserver.HttpHandler
import java.net.http.HttpClient
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.jetbrains.kmp.resolver.auth.BasicRepositoryCredentials
import org.jetbrains.kmp.resolver.auth.CredentialHelperOption
import org.jetbrains.kmp.resolver.auth.CredentialHelperProvider
import org.jetbrains.kmp.resolver.auth.RepositoryCredentials
import org.jetbrains.kmp.resolver.auth.RequestCredentials
import org.jetbrains.kmp.resolver.auth.withCredentialHelperCredentials
import org.jetbrains.kmp.resolver.auth.writeFakeCredentialHelper
import org.jetbrains.kmp.resolver.http.RetryPolicy
import org.jetbrains.kmp.resolver.http.jdkHttpClient
import org.jetbrains.kmp.resolver.http.respond
import org.jetbrains.kmp.resolver.http.urlOf
import org.jetbrains.kmp.resolver.http.withAuthenticatingHttpClient
import org.jetbrains.kmp.resolver.http.withLocalHttpServer

class ArtifactUrlResolverTest {
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val authorizations = ConcurrentHashMap<String, List<String>>()

    /**
     * `/ok` exists, `/missing` does not, `/flaky` fails twice before existing, `/down` always
     * fails, `/throttled` fails once asking to retry after a second.
     */
    private val artifacts = HttpHandler { exchange ->
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

    private fun basic(repositoryUrl: String) =
        BasicRepositoryCredentials(
            listOf(
                RepositoryCredentials(
                    repositoryUrl = repositoryUrl,
                    username = "alice",
                    password = "s3cret",
                ),
            ),
        )

    @Test
    fun `existing and missing artifacts`() = runBlocking {
        withLocalHttpServer(artifacts) { server ->
            jdkHttpClient(connectTimeout = 5.seconds).use { httpClient ->
                val resolver = resolver(httpClient)

                assertEquals(
                    ArtifactFile.Resolved(server.urlOf("/ok")),
                    resolver.artifactExistsAt(server.urlOf("/ok")),
                )
                assertEquals(
                    ArtifactFile.NotFound,
                    resolver.artifactExistsAt(server.urlOf("/missing")),
                )
            }
        }
        assertEquals(emptyList(), authorizations["/ok"])
    }

    @Test
    fun `results are cached per URL`() = runBlocking {
        withLocalHttpServer(artifacts) { server ->
            jdkHttpClient(connectTimeout = 5.seconds).use { httpClient ->
                val resolver = resolver(httpClient)

                repeat(3) { resolver.artifactExistsAt(server.urlOf("/ok")) }
            }
        }
        assertEquals(1, hits["/ok"]?.get())
    }

    @Test
    fun `server errors are retried`() = runBlocking {
        withLocalHttpServer(artifacts) { server ->
            jdkHttpClient(connectTimeout = 5.seconds).use { httpClient ->
                val resolver = resolver(httpClient)

                assertEquals(
                    ArtifactFile.Resolved(server.urlOf("/flaky")),
                    resolver.artifactExistsAt(server.urlOf("/flaky")),
                )
                assertEquals(
                    ArtifactFile.NotFound,
                    resolver.artifactExistsAt(server.urlOf("/down")),
                )
                assertEquals(
                    ArtifactFile.Resolved(server.urlOf("/throttled")),
                    resolver.artifactExistsAt(server.urlOf("/throttled")),
                )
            }
        }
        assertEquals(3, hits["/flaky"]?.get())
        assertEquals(6, hits["/down"]?.get())
        assertEquals(2, hits["/throttled"]?.get())
    }

    @Test
    fun `repository credentials are sent as basic authentication`() = runBlocking {
        withLocalHttpServer(artifacts) { server ->
            withAuthenticatingHttpClient(
                credentials = basic(server.urlOf("/")),
                connectTimeout = 5.seconds,
            ) { httpClient ->
                resolver(httpClient).artifactExistsAt(server.urlOf("/ok"))
            }
        }
        assertEquals(listOf("Basic YWxpY2U6czNjcmV0"), authorizations["/ok"])
    }

    private val directory: Path = createTempDirectory("artifact-url-resolver")

    @Test
    fun `credential helper headers take precedence over repository credentials`() = runBlocking {
        val helper =
            writeFakeCredentialHelper(
                directory = directory,
                name = "helper",
                stdout = """{"headers": {"Authorization": ["Bearer token"]}}""",
                stderr = "",
                exitCode = 0,
                delay = Duration.ZERO,
                requestLog = directory.resolve("requests.log"),
            )
        withLocalHttpServer(artifacts) { server ->
            withCredentialHelperCredentials(
                provider =
                    CredentialHelperProvider(
                        listOf(CredentialHelperOption.parse("localhost=$helper")),
                    ),
                timeout = 10.seconds,
                cacheDuration = 30.minutes,
                clock = Clock.System,
                workingDir = directory,
            ) { helperCredentials ->
                withAuthenticatingHttpClient(
                    credentials =
                        RequestCredentials.firstApplicable(
                            listOf(helperCredentials, basic(server.urlOf("/"))),
                        ),
                    connectTimeout = 5.seconds,
                ) { httpClient ->
                    resolver(httpClient).artifactExistsAt(server.urlOf("/ok"))
                }
            }
        }
        assertEquals(listOf("Bearer token"), authorizations["/ok"])
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
