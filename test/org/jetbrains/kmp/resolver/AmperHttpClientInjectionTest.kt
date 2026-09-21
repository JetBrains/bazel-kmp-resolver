package org.jetbrains.kmp.resolver

import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.dependency.resolution.MavenRepository
import java.net.http.HttpClient
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Guards the one piece of Amper-internal coupling this resolver relies on.
 *
 * Amper can only send HTTP Basic auth, so anything else (a bearer token from a Bazel credential helper) has to be
 * attached by swapping the `java.net.http.HttpClient` it downloads with, which is injected under a key whose name
 * is internal to Amper. If an Amper upgrade ever renames that key, the injection silently stops taking effect and
 * every authenticated download starts failing with a 401 — this test fails first instead.
 */
class AmperHttpClientInjectionTest {
    @Test
    fun `the amper resolution downloads through the injected client`() = runBlocking {
        // every request 404s: we only care about what was sent, not about completing a resolution
        RecordingHttpServer(statusCode = 404).use { server ->
            val repositoryUrl = "${server.baseUrl}/maven2"
            val credentials = RepositoryCredentialsResolver(
                listOf(
                    RepositoryCredentials(
                        repositoryUrl = repositoryUrl,
                        headers = mapOf("Authorization" to listOf("Bearer resolved-token")),
                    ),
                ),
            )
            val artifactResolver = ArtifactUrlResolver(
                allowedConcurrentConnections = 4,
                connectTimeout = 5.seconds,
                requestTimeout = 5.seconds,
            )
            artifactResolver.use {
                val resolver = MultiplatformResolver(
                    cachePath = createTempDirectory("injection-test"),
                    repositories = listOf(MavenRepository(repositoryUrl)),
                    substitutions = emptyMap(),
                    artifactResolver = artifactResolver,
                    npmResolver = NpmResolver(
                        nodeExecutable = NodeDistribution.node,
                        npmCliJs = NodeDistribution.npmCliJs,
                        registryUrl = null,
                        packageVersionOverrides = emptyMap(),
                        workDir = createTempDirectory("injection-test-npm"),
                    ),
                    credentialAwareHttpClient = CredentialAwareHttpClient(HttpClient.newHttpClient(), credentials),
                )

                // the resolution cannot succeed against an empty repository, the assertions are on what it sent
                runCatching { resolver.resolve(listOf("org.example:does-not-exist:1.0.0")) }
            }

            assertTrue(
                server.requests.isNotEmpty(),
                "the Amper resolution did not go through the injected client, the `${AMPER_HTTP_CLIENT_KEY.name}` " +
                    "cache key it reads its client from has most likely been renamed upstream",
            )
            assertEquals(
                listOf(listOf("Bearer resolved-token")).toString(),
                server.requests.map { it.authorizations }.distinct().toString(),
                "every request of the resolution should carry the resolved credentials",
            )
        }
    }
}
