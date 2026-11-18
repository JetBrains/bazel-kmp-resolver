package org.jetbrains.kmp.resolver

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import org.jetbrains.amper.dependency.resolution.MavenRepository
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.kmp.resolver.auth.Authentication
import org.jetbrains.kmp.resolver.auth.RequestCredentials
import org.jetbrains.kmp.resolver.http.RetryPolicy
import org.jetbrains.kmp.resolver.http.withAuthenticatingHttpClient
import org.opentest4j.AssertionFailedError
import org.opentest4j.FileInfo

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
                    if (ResolutionPlatform.WASM_JS in platforms)
                        NpmResolver(
                            nodeExecutable = NodeDistribution.node,
                            npmCliJs = NodeDistribution.npmCliJs,
                            registryUrl = null,
                            packageVersionOverrides = emptyMap(),
                            workDir = cachePath,
                        )
                    else null,
            ),
        )
    }

/** Credentials applying to no request, for resolutions against public repositories. */
internal val noCredentials = RequestCredentials { Authentication.NotApplicable }

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
    val actualJson = bazelManifestJson.encodeToString(BazelManifest.serializer(), actual)

    // The manifest is round-tripped to check that the platform, the only discriminator of a
    // variant,
    // discriminates it on deserialization as well.
    assertEquals(actual, bazelManifestJson.decodeFromString(BazelManifest.serializer(), actualJson))

    TestResourceReader.readResource(manifestResourceFilepath).use { expected ->
        val expectedManifest = expected.readAllBytes()
        assertEquals(expectedManifest.toString(Charsets.UTF_8), actualJson)
    }
}

/**
 * Thrown when the actual content does not match the expected content of [expectedFile].
 *
 * Both comparison values are file-backed because the test runner only emits IDE file-diff metadata
 * when both values are [FileInfo]s. The actual content is saved under `build/test-diffs`; the
 * expected file is never modified.
 */
class FileComparisonFailure(
    expectedFile: Path,
    expected: String,
    actual: String,
) :
    AssertionFailedError(
        "Actual content differs from '${expectedFile.absolute()}'",
        PresentableFileInfo(expectedFile.absolute().toString(), expected),
        actualFileInfo(actual),
    )

private fun actualFileInfo(text: String): FileInfo {
    val directory = Path.of("build/test-diffs").absolute()
    Files.createDirectories(directory)
    val path = Files.createTempFile(directory, "actual-", ".txt")
    Files.writeString(path, text, Charsets.UTF_8)
    return PresentableFileInfo(path.toString(), text)
}

private class PresentableFileInfo(
    path: String,
    private val text: String,
) : FileInfo(path, text.toByteArray(Charsets.UTF_8)) {
    override fun toString(): String = text
}

fun String.sanitizedLineEndings(): String {
    return replace(Regex("\\v"), System.lineSeparator())
        .removePrefix(System.lineSeparator())
        .removeSuffix(System.lineSeparator())
}
