package org.jetbrains.kmp.resolver

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import org.jetbrains.amper.dependency.resolution.MavenRepository
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

// TODO: would be nice to mock Maven repositories to avoid real HTTP calls
class MultiplatformResolverTest {
    @OptIn(ExperimentalSerializationApi::class, ExperimentalPathApi::class)
    @Test
    fun `multi-repository resolution`() = runBlocking {
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
                cachePath = createTempDirectory("resolution-cache"),
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
                cachePath = createTempDirectory("resolution-cache"),
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
                cachePath = createTempDirectory("resolution-cache"),
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

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `dependency on JVM-only dependencies are excluded`() = runBlocking {
        val repositories = listOf(
            "https://repo1.maven.org/maven2",
            "https://cache-redirector.jetbrains.com/packages.jetbrains.team/maven/p/grazi/grazie-platform-public",
        )
        val coordinates = listOf(
            "ai.jetbrains.code.files:code-files-model:1.0.0-beta.167",
        )
        val artifactResolver = ArtifactUrlResolver(
            allowedConcurrentConnections = 100,
            connectTimeout = 30.seconds,
            requestTimeout = 30.seconds,
        )
        val actual = artifactResolver.use { artifactResolver ->
            val resolver = MultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories.map { MavenRepository(it) },
                artifactResolver = artifactResolver,
                substitutions = emptyMap(),
            )
            resolver.resolve(coordinates)
        }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            libraries = actual,
            manifestResourceFilepath = "manifest-no_jvm_deps.json",
        )
    }

    @OptIn(ExperimentalSerializationApi::class, ExperimentalPathApi::class)
    @Test
    fun `ensure hash of artifacts are resolved`(): Unit = runBlocking {
        val repositories = listOf(
            "https://repo1.maven.org/maven2",
        )
        val coordinates = listOf(
            "com.github.ajalt.clikt:clikt-core:5.0.3", // previous implementation was unable to resolve the hahs of `com/github/ajalt/clikt/clikt-core-wasm-js/5.0.3/clikt-core-wasm-js-5.0.3-sources.jar`
        )
        val artifactResolver = ArtifactUrlResolver(
            allowedConcurrentConnections = 100,
            connectTimeout = 30.seconds,
            requestTimeout = 30.seconds,
        )

        val cache = createTempDirectory("resolution-cache")
        cache.deleteRecursively()
        artifactResolver.use { artifactResolver ->
            val resolver = MultiplatformResolver(
                cachePath = cache,
                repositories = repositories.map { MavenRepository(it) },
                artifactResolver = artifactResolver,
                substitutions = emptyMap()
            )
            assertDoesNotThrow {
                resolver.resolve(coordinates)
            }
        }
    }
}
