package org.jetbrains.kmp.resolver

import java.io.InputStream
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import org.jetbrains.amper.dependency.resolution.MavenRepository
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.kmp.resolver.auth.Authentication
import org.jetbrains.kmp.resolver.auth.RequestCredentials
import org.jetbrains.kmp.resolver.http.RetryPolicy
import org.jetbrains.kmp.resolver.http.withAuthenticatingHttpClient

object TestResourceReader {
    fun readResource(path: String): InputStream {
        return this::class.java.getResourceAsStream("/$path") ?: error("Resource not found: $path")
    }
}

/**
 * Runs [block] with a [MultiplatformResolver] wired to the hermetic [NodeDistribution] provisioned
 * by the `node` build plugin, so the tests never depend on a node/npm installation of the host, and
 * to an authenticating HTTP client closed once [block] returns.
 */
internal suspend fun <T> testMultiplatformResolver(
    cachePath: Path,
    repositories: List<String>,
    substitutions: Substitutions,
    platforms: Set<ResolutionPlatform>,
    credentials: RequestCredentials,
    block: suspend (MultiplatformResolver) -> T,
): T =
    withAuthenticatingHttpClient(credentials = credentials, connectTimeout = 30.seconds) {
        httpClient ->
        block(
            MultiplatformResolver(
                cachePath = cachePath,
                platforms = platforms,
                repositories = repositories.map { MavenRepository(it) },
                httpClient = httpClient,
                artifactResolver =
                    ArtifactUrlResolver(
                        httpClient = httpClient,
                        allowedConcurrentConnections = 32,
                        requestTimeout = 10.seconds,
                        retryPolicy = RetryPolicy.DEFAULT,
                    ),
                substitutions = substitutions,
                npmResolver =
                    NpmResolver(
                        nodeExecutable = NodeDistribution.node,
                        npmCliJs = NodeDistribution.npmCliJs,
                        registryUrl = null,
                        packageVersionOverrides = emptyMap(),
                        workDir = cachePath,
                    ),
            ),
        )
    }

/** Credentials applying to no request, for resolutions against public repositories. */
internal val noCredentials = RequestCredentials { Authentication.NotApplicable }

private val json = Json {
    prettyPrintIndent = "  "
    prettyPrint = true
}

/**
 * Asserts using the given [manifestResourceFilepath] to improve readability of diff in tests, it's
 * easier to read a JSON diff.
 */
internal fun assertUsingManifest(
    coordinates: List<String>,
    repositories: List<String>,
    libraries: List<MultiplatformVariant>,
    manifestResourceFilepath: String,
) {
    val actual =
        BazelManifest(
            askedCoordinates = coordinates.sorted(),
            askedRepositories = repositories.sorted(),
            libraries = libraries.asLibraries(),
        )
    val actualJson = json.encodeToString(BazelManifest.serializer(), actual)

    TestResourceReader.readResource(manifestResourceFilepath).use { expected ->
        val expectedManifest = expected.readAllBytes()
        assertEquals(expectedManifest.toString(Charsets.UTF_8), actualJson)
    }
}
