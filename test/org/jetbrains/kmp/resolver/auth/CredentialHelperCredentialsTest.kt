package org.jetbrains.kmp.resolver.auth

import java.net.URI
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.measureTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class CredentialHelperCredentialsTest {
    private val directory: Path = createTempDirectory("credential-helper")

    private val requestLog: Path = directory.resolve("requests.log")
    private val bearer = Authentication.Headers(mapOf("Authorization" to listOf("Bearer token")))

    private fun helper(
        stdout: String,
        stderr: String,
        exitCode: Int,
        delay: Duration,
    ): CredentialHelperOption =
        CredentialHelperOption(
            scope = CredentialHelperScope(host = "repo.example.com", includesSubdomains = false),
            path =
                writeFakeCredentialHelper(
                        directory = directory,
                        name = "helper",
                        stdout = stdout,
                        stderr = stderr,
                        exitCode = exitCode,
                        delay = delay,
                        requestLog = requestLog,
                    )
                    .toString(),
        )

    private suspend fun <T> withCredentials(
        helper: CredentialHelperOption,
        timeout: Duration,
        cacheDuration: Duration,
        clock: Clock,
        block: suspend (CredentialHelperCredentials) -> T,
    ): T =
        withCredentialHelperCredentials(
            provider = CredentialHelperProvider(listOf(helper)),
            timeout = timeout,
            cacheDuration = cacheDuration,
            clock = clock,
            workingDir = directory,
            block = block,
        )

    private fun requests(): List<String> =
        if (requestLog.exists()) requestLog.readLines() else emptyList()

    private val artifact = URI("https://repo.example.com/com/example/a/1.0/a-1.0.jar")

    @Test
    fun `returns the headers of the helper and sends it the URI`() = runBlocking {
        withCredentials(
            helper =
                helper(
                    stdout =
                        """{"headers": {"Authorization": ["Bearer token"], "X-Two": ["1", "2"]}}""",
                    stderr = "",
                    exitCode = 0,
                    delay = Duration.ZERO,
                ),
            timeout = 10.seconds,
            cacheDuration = 30.minutes,
            clock = Clock.System,
        ) { credentials ->
            val headers = credentials.authenticationFor(artifact)

            assertEquals(
                Authentication.Headers(
                    mapOf("Authorization" to listOf("Bearer token"), "X-Two" to listOf("1", "2")),
                ),
                headers,
            )
            assertEquals(listOf("""{"uri":"$artifact"}"""), requests())
        }
    }

    @Test
    fun `an answer without headers yields none`() = runBlocking {
        withCredentials(
            helper =
                helper(
                    stdout = """{"unknown": 1}""",
                    stderr = "",
                    exitCode = 0,
                    delay = Duration.ZERO,
                ),
            timeout = 10.seconds,
            cacheDuration = 30.minutes,
            clock = Clock.System,
        ) { credentials ->
            assertEquals(
                Authentication.Headers(emptyMap()),
                credentials.authenticationFor(artifact),
            )
        }
    }

    @Test
    fun `concurrent requests of one URI invoke the helper once`() = runBlocking {
        withCredentials(
            helper =
                helper(
                    stdout = """{"headers": {"Authorization": ["Bearer token"]}}""",
                    stderr = "",
                    exitCode = 0,
                    delay = 200.milliseconds,
                ),
            timeout = 10.seconds,
            cacheDuration = 30.minutes,
            clock = Clock.System,
        ) { credentials ->
            val headers = coroutineScope {
                (1..20).map { async { credentials.authenticationFor(artifact) } }.awaitAll()
            }

            assertTrue(headers.all { it == bearer })
            assertEquals(1, requests().size)
        }
    }

    @Test
    fun `distinct URIs invoke the helper separately and are cached separately`() = runBlocking {
        withCredentials(
            helper =
                helper(
                    stdout = """{"headers": {"Authorization": ["Bearer token"]}}""",
                    stderr = "",
                    exitCode = 0,
                    delay = Duration.ZERO,
                ),
            timeout = 10.seconds,
            cacheDuration = 30.minutes,
            clock = Clock.System,
        ) { credentials ->
            val other = URI("https://repo.example.com/com/example/a/1.0/a-1.0.pom")

            credentials.authenticationFor(artifact)
            credentials.authenticationFor(other)
            credentials.authenticationFor(artifact)
            credentials.authenticationFor(other)

            assertEquals(listOf("""{"uri":"$artifact"}""", """{"uri":"$other"}"""), requests())
        }
    }

    @Test
    fun `a host outside the helper scope never invokes it`() = runBlocking {
        withCredentials(
            helper =
                helper(
                    stdout = """{"headers": {"Authorization": ["Bearer token"]}}""",
                    stderr = "",
                    exitCode = 0,
                    delay = Duration.ZERO,
                ),
            timeout = 10.seconds,
            cacheDuration = 30.minutes,
            clock = Clock.System,
        ) { credentials ->
            assertEquals(
                Authentication.NotApplicable,
                credentials.authenticationFor(URI("https://repo1.maven.org/maven2/a.jar")),
            )
            assertFalse(requestLog.exists())
        }
    }

    @Test
    fun `an answer is cached until it expires`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        withCredentials(
            helper =
                helper(
                    stdout =
                        """{"headers": {"Authorization": ["Bearer token"]}, "expires": "2026-01-01T02:00:00+01:00"}""",
                    stderr = "",
                    exitCode = 0,
                    delay = Duration.ZERO,
                ),
            timeout = 10.seconds,
            cacheDuration = 30.minutes,
            clock = clock,
        ) { credentials ->
            assertEquals(bearer, credentials.authenticationFor(artifact))
            clock.advance(59.minutes)
            assertEquals(bearer, credentials.authenticationFor(artifact))
            assertEquals(1, requests().size)
            clock.advance(2.minutes)
            assertEquals(bearer, credentials.authenticationFor(artifact))
            assertEquals(2, requests().size)
        }
    }

    @Test
    fun `an answer without expiry is cached for the cache duration`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        withCredentials(
            helper =
                helper(
                    stdout = """{"headers": {"Authorization": ["Bearer token"]}}""",
                    stderr = "",
                    exitCode = 0,
                    delay = Duration.ZERO,
                ),
            timeout = 10.seconds,
            cacheDuration = 30.minutes,
            clock = clock,
        ) { credentials ->
            credentials.authenticationFor(artifact)
            clock.advance(29.minutes)
            credentials.authenticationFor(artifact)
            assertEquals(1, requests().size)
            clock.advance(2.minutes)
            credentials.authenticationFor(artifact)
            assertEquals(2, requests().size)
        }
    }

    @Test
    fun `a failing helper fails the request with its stderr and is invoked again next time`() =
        runBlocking {
            withCredentials(
                helper =
                    helper(
                        stdout = "",
                        stderr = "run helper login first",
                        exitCode = 3,
                        delay = Duration.ZERO,
                    ),
                timeout = 10.seconds,
                cacheDuration = 30.minutes,
                clock = Clock.System,
            ) { credentials ->
                val failure =
                    assertFailsWith<CredentialHelperException> {
                        credentials.authenticationFor(artifact)
                    }
                assertTrue("exit code 3" in failure.message!!, failure.message)
                assertTrue("run helper login first" in failure.message!!, failure.message)
                assertFailsWith<CredentialHelperException> {
                    credentials.authenticationFor(artifact)
                }
                assertEquals(2, requests().size)
            }
        }

    @Test
    fun `an invalid answer fails the request`() = runBlocking {
        withCredentials(
            helper = helper(stdout = "not json", stderr = "", exitCode = 0, delay = Duration.ZERO),
            timeout = 10.seconds,
            cacheDuration = 30.minutes,
            clock = Clock.System,
        ) { credentials ->
            val failure =
                assertFailsWith<CredentialHelperException> {
                    credentials.authenticationFor(artifact)
                }
            assertTrue("not json" in failure.message!!, failure.message)
        }
    }

    @Test
    fun `an invalid expiry fails the request`() = runBlocking {
        withCredentials(
            helper =
                helper(
                    stdout = """{"headers": {}, "expires": "tomorrow"}""",
                    stderr = "",
                    exitCode = 0,
                    delay = Duration.ZERO,
                ),
            timeout = 10.seconds,
            cacheDuration = 30.minutes,
            clock = Clock.System,
        ) { credentials ->
            val failure =
                assertFailsWith<CredentialHelperException> {
                    credentials.authenticationFor(artifact)
                }
            assertTrue("tomorrow" in failure.message!!, failure.message)
        }
    }

    @Test
    fun `a helper not answering in time fails the request`() = runBlocking {
        withCredentials(
            helper =
                helper(
                    stdout = """{"headers": {}}""",
                    stderr = "",
                    exitCode = 0,
                    delay = 10.seconds,
                ),
            timeout = 300.milliseconds,
            cacheDuration = 30.minutes,
            clock = Clock.System,
        ) { credentials ->
            val elapsed = measureTime {
                assertFailsWith<CredentialHelperException> {
                    credentials.authenticationFor(artifact)
                }
            }
            assertTrue(
                elapsed < 5.seconds,
                "the helper process should have been killed, took $elapsed",
            )
        }
    }

    @Test
    fun `cancelling a caller cancels the invocation it awaits and kills its helper`() =
        runBlocking {
            val request = launch {
                withCredentials(
                    helper =
                        helper(
                            stdout = """{"headers": {}}""",
                            stderr = "",
                            exitCode = 0,
                            delay = 10.seconds,
                        ),
                    timeout = 60.seconds,
                    cacheDuration = 30.minutes,
                    clock = Clock.System,
                ) { credentials ->
                    credentials.authenticationFor(artifact)
                }
            }
            while (requests().isEmpty()) {
                delay(20.milliseconds)
            } // the helper is running once it logged its request

            val elapsed = measureTime { request.cancelAndJoin() }
            assertTrue(
                elapsed < 5.seconds,
                "the helper process should have been killed, took $elapsed",
            )
        }

    @Test
    fun `a missing helper fails the request`() = runBlocking {
        withCredentials(
            helper =
                CredentialHelperOption(
                    scope = null,
                    path = directory.resolve("missing-helper").toString(),
                ),
            timeout = 10.seconds,
            cacheDuration = 30.minutes,
            clock = Clock.System,
        ) { credentials ->
            assertFailsWith<CredentialHelperException> { credentials.authenticationFor(artifact) }
            Unit
        }
    }
}
