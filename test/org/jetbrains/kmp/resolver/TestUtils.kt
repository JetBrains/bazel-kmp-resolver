package org.jetbrains.kmp.resolver

import kotlinx.serialization.json.Json
import org.jetbrains.amper.dependency.resolution.MavenRepository
import java.io.InputStream
import java.nio.file.Path
import kotlin.test.assertEquals

object TestResourceReader {
    fun readResource(path: String): InputStream {
        return this::class.java.getResourceAsStream("/$path") ?: error("Resource not found: $path")
    }
}

/**
 * Builds a [MultiplatformResolver] wired to the hermetic [NodeDistribution] provisioned by the `node` build plugin,
 * so the tests never depend on a node/npm installation of the host.
 */
internal fun testMultiplatformResolver(
    cachePath: Path,
    repositories: List<String>,
    artifactResolver: ArtifactUrlResolver,
    substitutions: Substitutions = emptyMap(),
): MultiplatformResolver = MultiplatformResolver(
    cachePath = cachePath,
    repositories = repositories.map { MavenRepository(it) },
    artifactResolver = artifactResolver,
    substitutions = substitutions,
    npmResolver = NpmResolver(
        nodeExecutable = NodeDistribution.node,
        npmCliJs = NodeDistribution.npmCliJs,
        registryUrl = null,
        packageVersionOverrides = emptyMap(),
        workDir = cachePath,
    ),
)

private val json = Json {
    prettyPrintIndent = "  "
    prettyPrint = true
}

/**
 * Asserts using the given [manifestResourceFilepath] to improve readability of diff in tests, it's easier to read
 * a JSON diff.
 */
internal fun assertUsingManifest(
    coordinates: List<String>,
    repositories: List<String>,
    libraries: List<MultiplatformVariant>,
    manifestResourceFilepath: String,
) {
    val actual = BazelManifest(
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
