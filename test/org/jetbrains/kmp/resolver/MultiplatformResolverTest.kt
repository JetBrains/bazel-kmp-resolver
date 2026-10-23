package org.jetbrains.kmp.resolver

import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

// TODO: would be nice to mock Maven repositories to avoid real HTTP calls
class MultiplatformResolverTest {
    @Test
    fun `artifact integrity uses base64 encoded raw digest bytes`() {
        val sha256 = MultiplatformLibraryArtifactIntegrity.Sha256(
            hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        )

        val bazelIntegrity = sha256.asBazelIntegrityString()

        assertEquals("sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=", bazelIntegrity)
        assertEquals(sha256, MultiplatformLibraryArtifactIntegrity.fromBazelIntegrityString(bazelIntegrity))
    }

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
            allowedConcurrentConnections = 32,
            connectTimeout = 30.seconds,
            requestTimeout = 10.seconds,
        )
        val actual = artifactResolver.use { artifactResolver ->
            val resolver = testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                artifactResolver = artifactResolver,
                platforms = setOf(ResolutionPlatform.WASM_JS),
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

    @Test
    fun `do not resolve JVM nodes`() = runBlocking {
        val repositories = listOf(
            "https://repo1.maven.org/maven2",
        )
        val coordinates = listOf(
            "org.jetbrains.kotlin:kotlin-reflect:2.4.0",
        )
        val artifactResolver = ArtifactUrlResolver(
            allowedConcurrentConnections = 32,
            connectTimeout = 30.seconds,
            requestTimeout = 10.seconds,
        )
        val actual = artifactResolver.use { artifactResolver ->
            val resolver = testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                artifactResolver = artifactResolver,
                platforms = setOf(ResolutionPlatform.WASM_JS),
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

    @Test
    fun `substituted resolution`() = runBlocking {
        val repositories = listOf(
            "https://repo1.maven.org/maven2",
        )
        val coordinates = listOf(
            "io.ktor:ktor-client-core:3.4.3",
        )
        val artifactResolver = ArtifactUrlResolver(
            allowedConcurrentConnections = 32,
            connectTimeout = 30.seconds,
            requestTimeout = 10.seconds,
        )
        val actual = artifactResolver.use { artifactResolver ->
            val resolver = testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                artifactResolver = artifactResolver,
                substitutions = mapOf(
                    "org.jetbrains.kotlinx:kotlinx-coroutines-core" to MultiplatformLibraryId.fromString("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1"),
                    "org.jetbrains.kotlinx:kotlinx-coroutines-core-wasm-js" to MultiplatformLibraryId.fromString("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-wasm-js:1.10.2-intellij-1"),
                ),
                platforms = setOf(ResolutionPlatform.WASM_JS),
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

    @Test
    fun `jvm resolution`() = runBlocking {
        val repositories = listOf(
            "https://repo1.maven.org/maven2",
        )
        val coordinates = listOf(
            "io.ktor:ktor-client-cio:3.5.0",
            "org.jetbrains.kotlin:kotlin-reflect:2.4.0", // JVM-only library, its id is its variantId
        )
        val artifactResolver = ArtifactUrlResolver(
            allowedConcurrentConnections = 32,
            connectTimeout = 30.seconds,
            requestTimeout = 10.seconds,
        )
        val actual = artifactResolver.use { artifactResolver ->
            val resolver = testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                artifactResolver = artifactResolver,
                platforms = setOf(ResolutionPlatform.JVM),
            )
            resolver.resolve(coordinates)
        }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            libraries = actual,
            manifestResourceFilepath = "manifest-jvm_resolution.json",
        )
    }

    @Test
    fun `wasmjs and jvm resolution`() = runBlocking {
        val repositories = listOf(
            "https://repo1.maven.org/maven2",
        )
        val coordinates = listOf(
            "io.ktor:ktor-client-core:3.4.3", // depends on the wasmJs-only `kotlinx-browser`, skipped on JVM
        )
        val artifactResolver = ArtifactUrlResolver(
            allowedConcurrentConnections = 32,
            connectTimeout = 30.seconds,
            requestTimeout = 10.seconds,
        )
        val actual = artifactResolver.use { artifactResolver ->
            val resolver = testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                artifactResolver = artifactResolver,
                // reversed on purpose: the manifest must not depend on the order platforms are requested in
                platforms = setOf(ResolutionPlatform.JVM, ResolutionPlatform.WASM_JS),
            )
            resolver.resolve(coordinates)
        }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            libraries = actual,
            manifestResourceFilepath = "manifest-wasmjs_jvm.json",
        )
    }

    @Test
    fun `substituted wasmjs and jvm resolution`() = runBlocking {
        val repositories = listOf(
            "https://repo1.maven.org/maven2",
        )
        val coordinates = listOf(
            "io.ktor:ktor-client-core:3.4.3",
        )
        val artifactResolver = ArtifactUrlResolver(
            allowedConcurrentConnections = 32,
            connectTimeout = 30.seconds,
            requestTimeout = 10.seconds,
        )
        val actual = artifactResolver.use { artifactResolver ->
            val resolver = testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                artifactResolver = artifactResolver,
                substitutions = mapOf(
                    "org.jetbrains.kotlinx:kotlinx-coroutines-core" to MultiplatformLibraryId.fromString("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1"),
                    // substituting the KMP library covers its platform-specific variants
                ),
                platforms = setOf(ResolutionPlatform.WASM_JS, ResolutionPlatform.JVM),
            )
            resolver.resolve(coordinates)
        }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            libraries = actual,
            manifestResourceFilepath = "manifest-with_substitutions_jvm.json",
        )
    }

    @Test
    fun `substituted library without KMP parent`() = runBlocking {
        val repositories = listOf(
            "https://repo1.maven.org/maven2",
        )
        val coordinates = listOf(
            "io.ktor:ktor-client-core:3.4.3", // depends on `kotlinx-coroutines-slf4j` on JVM, a JVM-only library without KMP parent
        )
        val artifactResolver = ArtifactUrlResolver(
            allowedConcurrentConnections = 32,
            connectTimeout = 30.seconds,
            requestTimeout = 10.seconds,
        )
        val actual = artifactResolver.use { artifactResolver ->
            val resolver = testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                artifactResolver = artifactResolver,
                substitutions = mapOf(
                    "org.jetbrains.kotlinx:kotlinx-coroutines-core" to MultiplatformLibraryId.fromString("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1"),
                    "org.jetbrains.kotlinx:kotlinx-coroutines-slf4j" to MultiplatformLibraryId.fromString("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-slf4j:1.10.2-intellij-1"),
                ),
                platforms = setOf(ResolutionPlatform.WASM_JS, ResolutionPlatform.JVM),
            )
            resolver.resolve(coordinates)
        }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            libraries = actual,
            manifestResourceFilepath = "manifest-substituted_without_kmp_parent.json",
        )
    }

    @Test
    fun `substitute declared directly along with the substituted library`() = runBlocking {
        val repositories = listOf(
            "https://repo1.maven.org/maven2",
        )
        val coordinates = listOf(
            "io.ktor:ktor-client-core:3.4.3", // depends on `kotlinx-coroutines-core`, substituted below
            "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1", // the substitute itself
        )
        val artifactResolver = ArtifactUrlResolver(
            allowedConcurrentConnections = 32,
            connectTimeout = 30.seconds,
            requestTimeout = 10.seconds,
        )
        val actual = artifactResolver.use { artifactResolver ->
            val resolver = testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                artifactResolver = artifactResolver,
                substitutions = mapOf(
                    "org.jetbrains.kotlinx:kotlinx-coroutines-core" to MultiplatformLibraryId.fromString("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1"),
                    "org.jetbrains.kotlinx:kotlinx-coroutines-core-wasm-js" to MultiplatformLibraryId.fromString("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-wasm-js:1.10.2-intellij-1"),
                    // a redundant variant key, as some generators emit: harmless
                ),
                platforms = setOf(ResolutionPlatform.WASM_JS, ResolutionPlatform.JVM),
            )
            resolver.resolve(coordinates)
        }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            libraries = actual,
            manifestResourceFilepath = "manifest-with_substitutions_and_substitute.json",
        )
    }

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
            allowedConcurrentConnections = 32,
            connectTimeout = 30.seconds,
            requestTimeout = 10.seconds,
        )
        val actual = artifactResolver.use { artifactResolver ->
            val resolver = testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                artifactResolver = artifactResolver,
                platforms = setOf(ResolutionPlatform.WASM_JS),
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

    @Test
    fun `klib-declared npm dependencies are resolved`() = runBlocking {
        val repositories = listOf(
            "https://repo1.maven.org/maven2",
        )
        val coordinates = listOf(
            "org.jetbrains.kotlinx:kotlinx-datetime:0.7.1", // its klib embeds a package.json requiring `@js-joda/core`
        )
        val artifactResolver = ArtifactUrlResolver(
            allowedConcurrentConnections = 32,
            connectTimeout = 30.seconds,
            requestTimeout = 10.seconds,
        )
        val actual = artifactResolver.use { artifactResolver ->
            val resolver = testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                artifactResolver = artifactResolver,
                platforms = setOf(ResolutionPlatform.WASM_JS),
            )
            resolver.resolve(coordinates)
        }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            libraries = actual,
            manifestResourceFilepath = "manifest-npm_datetime.json",
        )
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `ensure hash of artifacts are resolved`(): Unit = runBlocking {
        val repositories = listOf(
            "https://repo1.maven.org/maven2",
        )
        val coordinates = listOf(
            "com.github.ajalt.clikt:clikt-core:5.0.3", // previous implementation was unable to resolve the hahs of `com/github/ajalt/clikt/clikt-core-wasm-js/5.0.3/clikt-core-wasm-js-5.0.3-sources.jar`
        )
        val artifactResolver = ArtifactUrlResolver(
            allowedConcurrentConnections = 32,
            connectTimeout = 30.seconds,
            requestTimeout = 10.seconds,
        )

        val cache = createTempDirectory("resolution-cache")
        cache.deleteRecursively()
        artifactResolver.use { artifactResolver ->
            val resolver = testMultiplatformResolver(
                cachePath = cache,
                repositories = repositories,
                artifactResolver = artifactResolver,
                platforms = setOf(ResolutionPlatform.WASM_JS),
            )
            assertDoesNotThrow {
                resolver.resolve(coordinates)
            }
        }
    }
}
