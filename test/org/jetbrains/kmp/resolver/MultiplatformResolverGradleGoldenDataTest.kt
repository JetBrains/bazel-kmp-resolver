package org.jetbrains.kmp.resolver

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.dependency.resolution.PlatformType
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform.*
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.kotlin.tooling.core.withClosure

class MultiplatformResolverGradleGoldenDataTest {

    @Test
    fun `compare with gradle - kmp-coroutines`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
                "https://dl.google.com/dl/android/maven2",
            )

        val coordinates =
            listOf(
                "org.jetbrains.kotlin:kotlin-stdlib:2.4.21",
                "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0",
            )

        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms =
                    setOf(
                        JVM,
                        WASM_JS,
                        IOS_SIMULATOR_ARM64,
                        IOS_ARM64,
                        MACOS_ARM64,
                        LINUX_ARM64,
                        LINUX_X64,
                    ),
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertEqualsGradleDumps("kmp-coroutines", coordinates, actual)
    }

    @Test
    fun `compare with gradle - kmp-sqliter`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
                "https://dl.google.com/dl/android/maven2",
            )

        val coordinates = listOf("co.touchlab:sqliter-driver:1.4.0")

        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms =
                    setOf(
                        IOS_SIMULATOR_ARM64,
                        IOS_ARM64,
                        MACOS_ARM64,
                        LINUX_ARM64,
                        LINUX_X64,
                    ),
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertEqualsGradleDumps("kmp-sqliter", coordinates, actual)
    }

    @Test
    fun `compare with gradle - kmp-popular`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
                "https://dl.google.com/dl/android/maven2",
            )

        val coordinates =
            listOf(
                "org.jetbrains.kotlin:kotlin-stdlib:2.4.21",
                "io.ktor:ktor-client-core:3.6.1",
                "io.insert-koin:koin-core:4.2.2",
                "com.squareup.okio:okio:3.18.2",
                "org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2",
                "org.jetbrains.kotlinx:kotlinx-datetime:0.8.0",
                "org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0",
                "co.touchlab:kermit:2.2.0",
                "com.russhwolf:multiplatform-settings:1.3.0",
            )

        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms =
                    setOf(
                        JVM,
                        WASM_JS,
                        IOS_SIMULATOR_ARM64,
                        IOS_ARM64,
                        MACOS_ARM64,
                        LINUX_ARM64,
                        LINUX_X64,
                    ),
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertEqualsGradleDumps("kmp-popular", coordinates, actual)
    }
}

/**
 * Represents a given artifact resolved by this resolver, which can render a String used to match
 * the dump produced by the Gradle projects.
 *
 * Note: The "regex:" prefix will allow matching a given line using the specified regex. Matching
 * the dumps hashes only requires one of the hash algorithms to match.
 */
private data class GradleDumpEntry(
    val resolutionPlatform: ResolutionPlatform,
    val gav: String,
    val hash: MultiplatformLibraryArtifactIntegrity,
) {
    override fun toString(): String = buildString {
        appendLine(gav)
        when (hash.algorithm) {
            "sha512" -> {
                appendLine(hash.asBazelIntegrityString())
                appendLine("regex:sha256-.*")
                appendLine("regex:sha1-.*")
            }

            "sha256" -> {
                appendLine("regex:sha512-.*")
                appendLine(hash.asBazelIntegrityString())
                appendLine("regex:sha1-.*")
            }

            "sha1" -> {
                appendLine("regex:sha512-.*")
                appendLine("regex:sha256-.*")
                appendLine(hash.asBazelIntegrityString())
            }

            else -> error("Unknown hash algorithm: ${hash.algorithm}")
        }
    }
}

/**
 * Converts the given fileName (e.g. linuxArm64) to the expected resolution platform string (e.g.
 * linux_arm64) and resolves the resolution platform from it.
 */
private fun resolutionPlatformOfDumpFileName(fileName: String): ResolutionPlatform {
    val raw = fileName.split(".").first().replace(Regex("([A-Z])")) { "_${it.value.lowercase()}" }
    return resolutionPlatformOf(raw) ?: error("Unknown resolution platform: '$raw'")
}

private fun resolutionScopeOfDumpFileName(fileName: String): ResolutionScope {
    val raw = fileName.split(".")[1]
    return ResolutionScope.entries.first { it.name.equals(raw, ignoreCase = true) }
}

private fun assertEqualsGradleDumps(
    gradleProjectName: String,
    requested: List<String>,
    resolved: List<MultiplatformVariant>,
) {
    val goldenDataDirectory = Path("testResources/gradleProjects/golden-data/$gradleProjectName")
    if (!goldenDataDirectory.isDirectory())
        error("Gradle golden data  directory does not exist: $goldenDataDirectory")

    val requestedRootNodes = requested.flatMap { requested ->
        resolved
            .filter { it.variantId.gav == requested || it.id.gav == requested }
            .ifEmpty { error("No resolved variant found for requested: $requested") }
    }

    fun MultiplatformLibraryId.toSubstitutionId(): SubstitutionId = "$groupId:$artifactId"

    /* Manually reconstruct the 'compileDependencies' per platform */
    val compileDependencies =
        requestedRootNodes.withClosure<MultiplatformVariant> { variant ->
            variant.exportedDependencies.flatMap { dependencyId ->
                resolved
                    .filter { resolved ->
                        resolved.platform == variant.platform &&
                            (resolved.id.toSubstitutionId() == dependencyId ||
                                resolved.variantId.toSubstitutionId() == dependencyId)
                    }
                    .ifEmpty { error("No resolved variant found for requested: $dependencyId") }
            }
        }

    /* Manually reconstruct the 'runtimeDependencies' per platform */
    val runtimeDependencies =
        requestedRootNodes.withClosure<MultiplatformVariant> { variant ->
            (variant.dependencies + variant.exportedDependencies).flatMap { dependencyId ->
                resolved
                    .filter { resolved ->
                        resolved.platform == variant.platform &&
                            (resolved.id.toSubstitutionId() == dependencyId ||
                                resolved.variantId.toSubstitutionId() == dependencyId)
                    }
                    .ifEmpty { error("No resolved variant found for requested: $dependencyId") }
            }
        }

    fun Iterable<MultiplatformVariant>.toGradleDumpEntries():
        Map<ResolutionPlatform, List<GradleDumpEntry>> {
        return flatMap { variant ->
            when (variant) {
                is MultiplatformVariant.Jvm ->
                    listOf(
                        GradleDumpEntry(
                            variant.platform,
                            gav = variant.variantId.gav,
                            hash = variant.jar.integrity,
                        ),
                    )

                is MultiplatformVariant.WasmJs ->
                    listOf(
                        GradleDumpEntry(
                            variant.platform,
                            gav = variant.variantId.gav,
                            hash = variant.klib.integrity,
                        ),
                    )

                is MultiplatformVariant.Native ->
                    buildList {
                        add(
                            GradleDumpEntry(
                                variant.platform,
                                gav = variant.variantId.gav,
                                hash = variant.klib.integrity,
                            ),
                        )

                        variant.cinteropKlibs.forEach { cinterop ->
                            add(
                                GradleDumpEntry(
                                    variant.platform,
                                    gav = variant.variantId.gav,
                                    hash = cinterop.integrity,
                                ),
                            )
                        }
                    }
            }
        }
            .groupBy { it.resolutionPlatform }
    }

    val actualCompileScopeEntries = compileDependencies.toGradleDumpEntries()
    val actualRuntimeScopeEntries = runtimeDependencies.toGradleDumpEntries()

    /* Find the compile scope golden data files, per platform  */
    val expectCompileScopeFiles: Map<ResolutionPlatform, Path> =
        goldenDataDirectory
            .listDirectoryEntries()
            .filter {
                resolutionScopeOfDumpFileName(it.nameWithoutExtension) == ResolutionScope.COMPILE
            }
            .associateBy { resolutionPlatformOfDumpFileName(it.nameWithoutExtension) }

    /* Find the runtime scope golden data files, per platform */
    val expectRuntimeScopeFiles: Map<ResolutionPlatform, Path> =
        goldenDataDirectory
            .listDirectoryEntries()
            .filter {
                resolutionScopeOfDumpFileName(it.nameWithoutExtension) == ResolutionScope.RUNTIME
            }
            .associateBy { resolutionPlatformOfDumpFileName(it.nameWithoutExtension) }

    /**
     * Compares the expected dump against the actual dump. Note: Supports lines that start with
     * "regex:" and matches the given line by this pattern. This is used to support placeholders in
     * the expected dump.
     */
    fun matchDump(expectContent: String, actualContent: String): Boolean {
        val expectContentLines = expectContent.lines()
        val actualContentLines = actualContent.lines()
        if (expectContentLines.size != actualContentLines.size) {
            return false
        }

        actualContentLines.forEachIndexed { index, actualLine ->
            val expectContentLine = expectContentLines[index]
            if (actualLine == expectContentLine) return@forEachIndexed
            if (actualLine.startsWith("regex:")) {
                val pattern = actualLine.substringAfter("regex:")
                if (!Regex(pattern).matches(expectContentLine)) {
                    return false
                }
            } else {
                return false
            }
        }

        return true
    }

    fun assertDumpMatches(
        expectDumpFile: Path,
        actualEntries: List<GradleDumpEntry>,
    ) {
        val expectContent = expectDumpFile.readText().sanitizedLineEndings()

        val actualContent =
            actualEntries
                .sortedBy { it.gav }
                .joinToString(System.lineSeparator())
                .sanitizedLineEndings()

        if (!matchDump(expectContent, actualContent)) {
            throw FileComparisonFailure(expectDumpFile.absolute(), expectContent, actualContent)
        }
    }

    assertEquals(
        expectCompileScopeFiles.keys,
        actualCompileScopeEntries.keys,
        "Actual entries keys do not match expected dump files keys",
    )

    actualCompileScopeEntries.forEach { (platform, entries) ->
        assertDumpMatches(
            expectDumpFile = expectCompileScopeFiles.getValue(platform),
            actualEntries = entries,
        )
    }

    actualRuntimeScopeEntries.forEach { (platform, entries) ->
        /* K/N does not support runtime dependencies */
        if (platform.type == PlatformType.NATIVE) return@forEach
        assertDumpMatches(
            expectDumpFile = expectRuntimeScopeFiles.getValue(platform),
            actualEntries = entries,
        )
    }
}
