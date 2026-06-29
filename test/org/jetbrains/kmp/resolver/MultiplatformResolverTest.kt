package org.jetbrains.kmp.resolver

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import org.jetbrains.amper.dependency.resolution.MavenRepository
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class MultiplatformResolverTest {
    // TODO: would be nice to mock Maven repositories to avoid real HTTP calls
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `multi-repository resolution`() = runBlocking {
        val cache = createTempDirectory("simple")
        val repositories = listOf(
            "https://repo1.maven.org/maven2",
            "https://dl.google.com/dl/android/maven2",
        )
        val coordinates = listOf(
            "io.ktor:ktor-client-cio:3.5.0",
            "io.ktor:ktor-client-core:3.4.3",
            "org.jetbrains.kotlin:kotlin-reflect:2.4.0",
        )
        val artifactResolver = ArtifactUrlResolver(
            allowedConcurrentConnections = 100,
            connectTimeout = 30.seconds,
            requestTimeout = 30.seconds,
        )
        val actual = artifactResolver.use { artifactResolver ->
            val resolver = MultiplatformResolver(
                cachePath = cache,
                repositories = repositories.map { MavenRepository(it) },
                artifactResolver = artifactResolver,
                substitutions = emptyMap()
            )
            resolver.resolve(coordinates)
        }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            libraries = actual,
            manifestResourceFilepath = "manifest-ktor_resolution.json",
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `do not resolve JVM nodes`() = runBlocking {
        val cache = createTempDirectory("simple")
        val repositories = listOf(
            "https://repo1.maven.org/maven2",
        )
        val coordinates = listOf(
            "org.jetbrains.kotlin:kotlin-reflect:2.4.0",
        )
        val artifactResolver = ArtifactUrlResolver(
            allowedConcurrentConnections = 100,
            connectTimeout = 30.seconds,
            requestTimeout = 30.seconds,
        )
        val actual = artifactResolver.use { artifactResolver ->
            val resolver = MultiplatformResolver(
                cachePath = cache,
                repositories = repositories.map { MavenRepository(it) },
                artifactResolver = artifactResolver,
                substitutions = emptyMap()
            )
            resolver.resolve(coordinates)
        }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            libraries = actual,
            manifestResourceFilepath = "manifest-no_jvm.json",
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `substituted resolution`() = runBlocking {
        val cache = createTempDirectory("simple")
        val repositories = listOf(
            "https://repo1.maven.org/maven2",
        )
        val coordinates = listOf(
            "io.ktor:ktor-client-core:3.4.3",
        )
        val artifactResolver = ArtifactUrlResolver(
            allowedConcurrentConnections = 100,
            connectTimeout = 30.seconds,
            requestTimeout = 30.seconds,
        )
        val actual = artifactResolver.use { artifactResolver ->
            val resolver = MultiplatformResolver(
                cachePath = cache,
                repositories = repositories.map { MavenRepository(it) },
                artifactResolver = artifactResolver,
                substitutions = mapOf(
                    "org.jetbrains.kotlinx:kotlinx-coroutines-core" to MultiplatformLibraryId.fromString("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1"),
                    "org.jetbrains.kotlinx:kotlinx-coroutines-core-wasm-js" to MultiplatformLibraryId.fromString("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-wasm-js:1.10.2-intellij-1"),
                )
            )
            resolver.resolve(coordinates)
        }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            libraries = actual,
            manifestResourceFilepath = "manifest-with_substitutions.json",
        )
    }
}
