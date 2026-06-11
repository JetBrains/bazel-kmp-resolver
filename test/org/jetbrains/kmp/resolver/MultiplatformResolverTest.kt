package org.jetbrains.kmp.resolver

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.jetbrains.amper.dependency.resolution.MavenRepository
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class MultiplatformResolverTest {
    companion object {
        private val json = Json {
            prettyPrint = true
        }
    }

    // TODO: would be nice to mock Maven repositories to avoid real HTTP calls
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `resolve simple single dependency`() = runBlocking {
        val cache = createTempDirectory("simple")
        val repositories = listOf(
            "https://repo1.maven.org/maven2",
            "https://dl.google.com/dl/android/maven2",
        )
        val coordinates = listOf(
            "io.ktor:ktor-client-cio:3.5.0",
        )
        val resolver = MultiplatformResolver(
            cachePath = cache,
            repositories = repositories.map { MavenRepository(it) },
            stopAtFirstRepositoryMatch = true,
        )

        val actual = BazelManifest(
            askedCoordinates = coordinates.sorted(),
            askedRepositories = repositories.sorted(),
            libraries = resolver.resolve(coordinates).associateBy { it.id }.toSortedMap(),
        )
        val actualJson = json.encodeToString(BazelManifest.serializer(), actual)

        TestResourceReader.readResource("simple-manifest.json").use { expected ->
            val expectedManifest = expected.readAllBytes()
            assertEquals(expectedManifest.toString(Charsets.UTF_8), actualJson)
        }
    }
}
