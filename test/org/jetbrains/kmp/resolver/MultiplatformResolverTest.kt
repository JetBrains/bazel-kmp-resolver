package org.jetbrains.kmp.resolver

import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.extension
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.junit.jupiter.api.assertDoesNotThrow

// TODO: would be nice to mock Maven repositories to avoid real HTTP calls
class MultiplatformResolverTest {
    @Test
    fun `artifact integrity uses base64 encoded raw digest bytes`() {
        val sha256 =
            MultiplatformLibraryArtifactIntegrity.Sha256(
                hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            )

        val bazelIntegrity = sha256.asBazelIntegrityString()

        assertEquals("sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=", bazelIntegrity)
        assertEquals(
            sha256,
            MultiplatformLibraryArtifactIntegrity.fromBazelIntegrityString(bazelIntegrity),
        )
    }

    @Test
    fun `multi-repository resolution`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
                "https://dl.google.com/dl/android/maven2",
            )
        val coordinates =
            listOf(
                "io.ktor:ktor-client-cio:3.5.0",
                "io.ktor:ktor-client-core:3.4.3",
                "org.jetbrains.kotlin:kotlin-reflect:2.4.0",
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = setOf(ResolutionPlatform.WASM_JS),
                credentials = noCredentials,
            ) { resolver ->
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
    fun `plain JVM library does not leak into a native platform variant`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                // a plain JVM library, published with a POM only: no variant of it is expected for
                // native platforms, while its sources jar used to be collected into a `linux_x64`
                // variant
                // this library uses 'bundle' as packaging in its POM
                "com.google.guava:failureaccess:1.0.1",
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = setOf(ResolutionPlatform.LINUX_X64, ResolutionPlatform.JVM),
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            libraries = actual,
            manifestResourceFilepath = "manifest-jvm_library_native_platform.json",
        )
    }

    @Test
    fun `do not resolve JVM nodes`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "org.jetbrains.kotlin:kotlin-reflect:2.4.0",
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = setOf(ResolutionPlatform.WASM_JS),
                credentials = noCredentials,
            ) { resolver ->
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
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "io.ktor:ktor-client-core:3.4.3",
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions =
                    mapOf(
                        "org.jetbrains.kotlinx:kotlinx-coroutines-core" to
                            MultiplatformLibraryId.fromString(
                                "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1",
                            ),
                        "org.jetbrains.kotlinx:kotlinx-coroutines-core-wasm-js" to
                            MultiplatformLibraryId.fromString(
                                "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-wasm-js:1.10.2-intellij-1",
                            ),
                    ),
                platforms = setOf(ResolutionPlatform.WASM_JS),
                credentials = noCredentials,
            ) { resolver ->
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
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "io.ktor:ktor-client-cio:3.5.0",
                "org.jetbrains.kotlin:kotlin-reflect:2.4.0", // JVM-only library, its id is its
                // variantId
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = setOf(ResolutionPlatform.JVM),
                credentials = noCredentials,
            ) { resolver ->
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
    fun `jvm resolution - coroutines-core`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = setOf(ResolutionPlatform.JVM),
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            libraries = actual,
            manifestResourceFilepath = "manifest-jvm_resolution_coroutines_core.json",
        )
    }

    @Test
    fun `wasmjs and jvm resolution`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "io.ktor:ktor-client-core:3.4.3", // depends on the wasmJs-only `kotlinx-browser`,
                // skipped on JVM
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                // reversed on purpose: the manifest must not depend on the order platforms are
                // requested in
                platforms = setOf(ResolutionPlatform.JVM, ResolutionPlatform.WASM_JS),
                credentials = noCredentials,
            ) { resolver ->
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
    fun `jvm and ios_arm64 resolution`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "io.ktor:ktor-client-core:3.4.3",
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = setOf(ResolutionPlatform.JVM, ResolutionPlatform.IOS_ARM64),
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            libraries = actual,
            manifestResourceFilepath = "manifest-jvm_iosArm64.json",
        )
    }

    @Test
    fun `ios_simulator_arm64 and ios_arm64 resolution`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "io.ktor:ktor-client-core:3.4.3",
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms =
                    setOf(ResolutionPlatform.IOS_SIMULATOR_ARM64, ResolutionPlatform.IOS_ARM64),
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            libraries = actual,
            manifestResourceFilepath = "manifest-iosSimulatorArm64_iosArm64.json",
        )
    }

    @Test
    fun `android resolution`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
                "https://dl.google.com/dl/android/maven2",
            )
        val coordinates =
            listOf(
                "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2", // a jar
                "androidx.annotation:annotation:1.9.1", // a KMP library without Android variant,
                // falling back to its JVM variant
                "androidx.annotation:annotation-experimental:1.4.1", // an aar
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = setOf(ResolutionPlatform.ANDROID),
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            libraries = actual,
            manifestResourceFilepath = "manifest-android_resolution.json",
        )
    }

    @Test
    fun `android and jvm resolution`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
                "https://dl.google.com/dl/android/maven2",
            )
        val coordinates =
            listOf(
                "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2", // a jar
                "androidx.annotation:annotation:1.9.1", // a KMP library without Android variant,
                // falling back to its JVM variant
                "androidx.annotation:annotation-experimental:1.4.1", // an aar
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = setOf(ResolutionPlatform.ANDROID, ResolutionPlatform.JVM),
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            libraries = actual,
            manifestResourceFilepath = "manifest-android_jvm_resolution.json",
        )
    }

    @Test
    fun `library whose Android variant only declares dependencies redirects to them`() =
        runBlocking {
            val repositories =
                listOf(
                    "https://repo1.maven.org/maven2",
                    "https://dl.google.com/dl/android/maven2",
                )
            val coordinates =
                listOf(
                    // the Android variant of the JetBrains Compose runtime redirects to the
                    // AndroidX
                    // Compose ones, while its JVM variant is the jar of the module its desktop
                    // variant
                    // is published in
                    "org.jetbrains.compose.runtime:runtime:1.8.2",
                )
            val actual =
                testMultiplatformResolver(
                    cachePath = createTempDirectory("resolution-cache"),
                    repositories = repositories,
                    substitutions = emptyMap(),
                    platforms = setOf(ResolutionPlatform.ANDROID, ResolutionPlatform.JVM),
                    credentials = noCredentials,
                ) { resolver ->
                    resolver.resolve(coordinates)
                }

            assertUsingManifest(
                coordinates = coordinates,
                repositories = repositories,
                libraries = actual,
                manifestResourceFilepath = "manifest-compose_redirects.json",
            )
        }

    @Test
    fun `directly requested native module without KMP parent`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                // the linuxx64 variant itself, not the `kotlinx-datetime` umbrella: no node of
                // this graph has a KMP parent, and the requested module must still be collected
                "org.jetbrains.kotlinx:kotlinx-datetime-linuxx64:0.7.1",
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = setOf(ResolutionPlatform.LINUX_X64),
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            libraries = actual,
            manifestResourceFilepath = "manifest-native_module_requested_directly.json",
        )
    }

    @Test
    fun `substituted wasmjs and jvm resolution`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "io.ktor:ktor-client-core:3.4.3",
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions =
                    mapOf(
                        "org.jetbrains.kotlinx:kotlinx-coroutines-core" to
                            MultiplatformLibraryId.fromString(
                                "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1",
                            ),
                        // substituting the KMP library covers its platform-specific variants
                    ),
                platforms = setOf(ResolutionPlatform.WASM_JS, ResolutionPlatform.JVM),
                credentials = noCredentials,
            ) { resolver ->
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
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "io.ktor:ktor-client-core:3.4.3", // depends on `kotlinx-coroutines-slf4j` on JVM, a
                // JVM-only library without KMP parent
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions =
                    mapOf(
                        "org.jetbrains.kotlinx:kotlinx-coroutines-core" to
                            MultiplatformLibraryId.fromString(
                                "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1",
                            ),
                        "org.jetbrains.kotlinx:kotlinx-coroutines-slf4j" to
                            MultiplatformLibraryId.fromString(
                                "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-slf4j:1.10.2-intellij-1",
                            ),
                    ),
                platforms = setOf(ResolutionPlatform.WASM_JS, ResolutionPlatform.JVM),
                credentials = noCredentials,
            ) { resolver ->
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
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "io.ktor:ktor-client-core:3.4.3", // depends on `kotlinx-coroutines-core`,
                // substituted below
                "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1", // the substitute itself
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions =
                    mapOf(
                        "org.jetbrains.kotlinx:kotlinx-coroutines-core" to
                            MultiplatformLibraryId.fromString(
                                "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1",
                            ),
                        "org.jetbrains.kotlinx:kotlinx-coroutines-core-wasm-js" to
                            MultiplatformLibraryId.fromString(
                                "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-wasm-js:1.10.2-intellij-1",
                            ),
                        // a redundant variant key, as some generators emit: harmless
                    ),
                platforms = setOf(ResolutionPlatform.WASM_JS, ResolutionPlatform.JVM),
                credentials = noCredentials,
            ) { resolver ->
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
    fun `platform variant also requested directly by a POM-based library`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                // a pom-only JVM library depending directly on `kotlinx-coroutines-core-jvm` (of
                // the IntelliJ fork), listed first
                // so that the dependency graph walk meets that variant as its child before meeting
                // it as the KMP library's one
                "com.google.devtools.ksp:symbol-processing-aa-embeddable:2.3.6",
                "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1",
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = setOf(ResolutionPlatform.JVM),
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            libraries = actual,
            manifestResourceFilepath = "manifest-variant_requested_directly.json",
        )
    }

    @Test
    fun `dependency on JVM-only dependencies are excluded`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
                "https://cache-redirector.jetbrains.com/packages.jetbrains.team/maven/p/grazi/grazie-platform-public",
            )
        val coordinates =
            listOf(
                "ai.jetbrains.code.files:code-files-model:1.0.0-beta.167",
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = setOf(ResolutionPlatform.WASM_JS),
                credentials = noCredentials,
            ) { resolver ->
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
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "org.jetbrains.kotlinx:kotlinx-datetime:0.7.1", // its klib embeds a package.json
                // requiring `@js-joda/core`
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = setOf(ResolutionPlatform.WASM_JS),
                credentials = noCredentials,
            ) { resolver ->
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
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "com.github.ajalt.clikt:clikt-core:5.0.3", // previous implementation was unable to
                // resolve the hahs of
                // `com/github/ajalt/clikt/clikt-core-wasm-js/5.0.3/clikt-core-wasm-js-5.0.3-sources.jar`
            )
        val cache = createTempDirectory("resolution-cache")
        cache.deleteRecursively()
        testMultiplatformResolver(
            cachePath = cache,
            repositories = repositories,
            substitutions = emptyMap(),
            platforms = setOf(ResolutionPlatform.WASM_JS),
            credentials = noCredentials,
        ) { resolver ->
            assertDoesNotThrow {
                resolver.resolve(coordinates)
            }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `a reused cache resolves as an empty one, even with corrupted artifacts`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "org.jetbrains.kotlinx:kotlinx-datetime:0.7.1",
            )
        val cache = createTempDirectory("resolution-cache")
        val resolve: suspend () -> List<MultiplatformVariant> = {
            testMultiplatformResolver(
                cachePath = cache,
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = setOf(ResolutionPlatform.WASM_JS, ResolutionPlatform.JVM),
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }
        }

        val cold = resolve()
        val warm = resolve()
        val cachedArtifacts =
            cache.walk().filter { it.extension == "klib" || it.extension == "jar" }.toList()
        assertTrue(cachedArtifacts.isNotEmpty(), "no artifact was cached in $cache")
        cachedArtifacts.forEach { it.writeText("") }
        val corrupted = resolve()

        assertEquals(cold, warm)
        assertEquals(cold, corrupted)
    }
}
