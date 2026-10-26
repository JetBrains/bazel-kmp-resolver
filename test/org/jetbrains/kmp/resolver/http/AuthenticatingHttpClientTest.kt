package org.jetbrains.kmp.resolver.http

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.jetbrains.kmp.resolver.auth.Authentication
import org.jetbrains.kmp.resolver.auth.BasicRepositoryCredentials
import org.jetbrains.kmp.resolver.auth.RepositoryCredentials
import org.jetbrains.kmp.resolver.auth.RequestCredentials

class AuthenticatingHttpClientTest {
    private val seenHeaders = ConcurrentLinkedQueue<Headers>()

    /** Answers `ok` to every request, recording its headers. */
    private val echo = HttpHandler { exchange ->
        seenHeaders.add(exchange.requestHeaders)
        exchange.respond(200, "ok".toByteArray())
    }

    private suspend fun <T> withClient(
        credentials: RequestCredentials,
        block: suspend (HttpClient) -> T,
    ): T =
        withAuthenticatingHttpClient(
            credentials = credentials,
            connectTimeout = 10.seconds,
            block = block,
        )

    private fun basic(
        server: HttpServer,
        username: String,
        password: String,
    ): BasicRepositoryCredentials =
        BasicRepositoryCredentials(
            listOf(
                RepositoryCredentials(
                    repositoryUrl = server.urlOf("/"),
                    username = username,
                    password = password,
                ),
            ),
        )

    /**
     * Carries an `Authorization` header of its own, which credentials applying to the request
     * replace.
     */
    private fun request(url: String): HttpRequest =
        HttpRequest.newBuilder(URI(url))
            .GET()
            .header("Authorization", "Basic abc")
            .header("User-Agent", "test-agent")
            .build()

    private val bearer = RequestCredentials {
        Authentication.Headers(
            mapOf("authorization" to listOf("Bearer token"), "X-Two" to listOf("1", "2")),
        )
    }

    @Test
    fun `credential headers are added and replace headers of the same name`() = runBlocking {
        withLocalHttpServer(echo) { server ->
            withClient(bearer) { client ->
                val response =
                    client.sendAsync(request(server.urlOf("/a")), BodyHandlers.ofString()).await()

                assertEquals(200, response.statusCode())
                assertEquals("ok", response.body())
            }
        }
        val headers = seenHeaders.single()
        assertEquals(listOf("Bearer token"), headers["Authorization"])
        assertEquals(listOf("1", "2"), headers["X-Two"])
        assertEquals(listOf("test-agent"), headers["User-Agent"])
    }

    @Test
    fun `a request no credentials apply to is sent untouched`() = runBlocking {
        withLocalHttpServer(echo) { server ->
            withClient({ Authentication.NotApplicable }) { client ->
                client.sendAsync(request(server.urlOf("/a")), BodyHandlers.ofString()).await()
            }
        }
        assertEquals(listOf("Basic abc"), seenHeaders.single()["Authorization"])
    }

    @Test
    fun `repository credentials authenticate the requests under their URL`() = runBlocking {
        withLocalHttpServer(echo) { server ->
            withClient(basic(server, "alice", "s3cret")) { client ->
                client.sendAsync(request(server.urlOf("/a")), BodyHandlers.ofString()).await()
                client
                    .sendAsync(
                        request("http://127.0.0.1:${server.address.port}/a"),
                        BodyHandlers.ofString(),
                    )
                    .await()
            }
        }
        assertEquals(
            listOf(listOf("Basic YWxpY2U6czNjcmV0"), listOf("Basic abc")),
            seenHeaders.map { it["Authorization"] },
        )
    }

    @Test
    fun `credentials failing to resolve fail the request before it is sent`() = runBlocking {
        withLocalHttpServer(echo) { server ->
            withClient(RequestCredentials { error("no token") }) { client ->
                assertFailsWith<IllegalStateException> {
                    client.sendAsync(request(server.urlOf("/a")), BodyHandlers.ofString()).await()
                }
            }
        }
        assertNull(seenHeaders.peek())
    }

    @Test
    fun `blocking send is not supported`() = runBlocking {
        withLocalHttpServer(echo) { server ->
            withClient(bearer) { client ->
                assertFailsWith<UnsupportedOperationException> {
                    client.send(request(server.urlOf("/a")), BodyHandlers.ofString())
                }
            }
        }
        assertNull(seenHeaders.peek())
    }
}
