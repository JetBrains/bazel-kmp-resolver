package org.jetbrains.kmp.resolver

import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.kmp.resolver.auth.CredentialHelperOption
import org.jetbrains.kmp.resolver.auth.CredentialHelperProvider
import org.jetbrains.kmp.resolver.auth.RequestCredentials
import org.jetbrains.kmp.resolver.auth.withCredentialHelperCredentials
import org.jetbrains.kmp.resolver.auth.writeFakeCredentialHelper
import org.jetbrains.kmp.resolver.http.respond
import org.jetbrains.kmp.resolver.http.urlOf
import org.jetbrains.kmp.resolver.http.withLocalHttpServer

/**
 * Resolves against a local Maven repository accepting only requests authenticated by a credential
 * helper, which proves that Amper's downloads and the artifact probes both go through the
 * helper-aware HTTP client.
 */
class CredentialHelperResolutionTest {
    private val directory: Path = createTempDirectory("credential-helper-resolution")
    private val repository: Path =
        directory.resolve("repository").also { root ->
            val artifact = root.resolve("com/example/hello/1.0").createDirectories()
            artifact
                .resolve("hello-1.0.pom")
                .writeText(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>com.example</groupId>
                      <artifactId>hello</artifactId>
                      <version>1.0</version>
                      <packaging>jar</packaging>
                    </project>
                    """
                        .trimIndent(),
                )
            JarOutputStream(artifact.resolve("hello-1.0.jar").outputStream(), Manifest()).use { jar
                ->
                jar.putNextEntry(ZipEntry("com/example/Hello.txt"))
                jar.write("hello".toByteArray())
                jar.closeEntry()
            }
        }
    private val unauthenticatedPaths = ConcurrentHashMap.newKeySet<String>()

    /** Serves [repository] to requests carrying the helper's bearer token, 401 to the others. */
    private val authenticatedRepository = HttpHandler { exchange ->
        val path = exchange.requestURI.path
        val file = repository.resolve(path.removePrefix("/"))
        val digestAlgorithm =
            DIGESTS.entries.firstOrNull { (extension, _) -> path.endsWith(".$extension") }
        val digested = digestAlgorithm?.let {
            repository.resolve(path.removePrefix("/").removeSuffix(".${it.key}"))
        }
        when {
            exchange.requestHeaders["Authorization"] != listOf("Bearer test-token") -> {
                unauthenticatedPaths.add(path)
                exchange.respond(401, ByteArray(0))
            }

            file.isRegularFile() -> exchange.respond(200, file.readBytes())
            digested != null && digested.isRegularFile() ->
                exchange.respond(
                    200,
                    MessageDigest.getInstance(digestAlgorithm.value)
                        .digest(digested.readBytes())
                        .toHexString()
                        .toByteArray(),
                )

            else -> exchange.respond(404, ByteArray(0))
        }
    }

    private val requestLog: Path = directory.resolve("requests.log")

    private suspend fun <T> withHelperCredentials(block: suspend (RequestCredentials) -> T): T {
        val helper =
            writeFakeCredentialHelper(
                directory = directory,
                name = "helper",
                stdout = """{"headers": {"Authorization": ["Bearer test-token"]}}""",
                stderr = "",
                exitCode = 0,
                delay = Duration.ZERO,
                requestLog = requestLog,
            )
        return withCredentialHelperCredentials(
            provider =
                CredentialHelperProvider(listOf(CredentialHelperOption.parse("localhost=$helper"))),
            timeout = 10.seconds,
            cacheDuration = 30.minutes,
            clock = Clock.System,
            workingDir = directory,
            block = block,
        )
    }

    private suspend fun resolve(
        server: HttpServer,
        credentials: RequestCredentials,
    ): List<MultiplatformVariant> =
        testMultiplatformResolver(
            cachePath = directory.resolve("cache"),
            repositories = listOf(server.urlOf("/")),
            substitutions = emptyMap(),
            platforms = setOf(ResolutionPlatform.JVM),
            credentials = credentials,
        ) { resolver ->
            resolver.resolve(listOf("com.example:hello:1.0"))
        }

    @Test
    fun `Amper downloads and artifact probes authenticate through the credential helper`() =
        runBlocking {
            withLocalHttpServer(authenticatedRepository) { server ->
                withHelperCredentials { credentials ->
                    val variants = resolve(server, credentials)

                    val variant = assertIs<MultiplatformVariant.Jvm>(variants.single())
                    assertEquals(MultiplatformLibraryId("com.example", "hello", "1.0"), variant.id)
                    assertEquals(
                        listOf(server.urlOf("/com/example/hello/1.0/hello-1.0.jar")),
                        variant.jar.urls,
                    )
                }
            }
            assertEquals(emptySet(), unauthenticatedPaths)
            val requests = requestLog.readLines()
            assertTrue(requests.isNotEmpty())
            assertEquals(
                requests.distinct(),
                requests,
                "each URI should be asked to the helper once",
            )
        }

    @Test
    fun `without the credential helper the repository rejects the resolution`() {
        withLocalHttpServer(authenticatedRepository) { server ->
            assertFails { runBlocking { resolve(server, noCredentials) } }
        }
        assertTrue(unauthenticatedPaths.isNotEmpty())
        assertTrue(!requestLog.exists())
    }

    companion object {
        private val DIGESTS =
            mapOf("sha512" to "SHA-512", "sha256" to "SHA-256", "sha1" to "SHA-1", "md5" to "MD5")
    }
}
