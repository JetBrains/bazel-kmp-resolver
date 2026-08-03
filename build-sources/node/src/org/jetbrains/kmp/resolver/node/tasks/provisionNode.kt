package org.jetbrains.kmp.resolver.node.tasks

import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.kmp.resolver.node.Platform
import org.jetbrains.kmp.resolver.node.download
import org.jetbrains.kmp.resolver.node.extract
import org.jetbrains.kmp.resolver.node.models.NodeDistributionArchive
import org.jetbrains.kmp.resolver.node.normalizedArch
import org.jetbrains.kmp.resolver.node.normalizedOs
import org.jetbrains.kmp.resolver.node.verifySha256
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Provisions the hermetic Node.js distribution the tests run npm with, downloaded from the official
 * https://nodejs.org/dist CDN (pinned version and sha256, matching the `rules_nodejs` default of the Bazel side)
 * instead of resolving node/npm from the host.
 *
 * The distribution itself is extracted into [distributionDirectory]; [generatedResourcesDirectory] only receives a
 * small `node-distribution.properties` pointing at it, and is contributed to the test fragment (see `plugin.yaml`)
 * so that the tests can locate the distribution from their classpath.
 */
@OptIn(ExperimentalPathApi::class)
@TaskAction
fun provisionNode(
    version: String,
    distributions: List<NodeDistributionArchive>,
    @Output distributionDirectory: Path,
    @Output generatedResourcesDirectory: Path,
) {
    val platform = Platform.current()
    val distribution = distributions
        .singleOrNull { it.os.normalizedOs() == platform.os && it.arch.normalizedArch() == platform.arch }
        ?: error(
            "No Node.js distribution configured for ${platform.suffix}. Configured platforms: " +
                    distributions.joinToString { "${it.os}-${it.arch}" }
        )

    val marker = distributionDirectory.resolve(".node-provisioned")
    val expectedMarker = """
        version=$version
        url=${distribution.url}
        sha256=${distribution.sha256.lowercase(Locale.ROOT)}
    """.trimIndent()

    val node = distribution.nodePath.resolveUnder(distributionDirectory)
    val npmCliJs = distribution.npmCliJsPath.resolveUnder(distributionDirectory)

    when {
        marker.exists() && marker.readText().trim() == expectedMarker && node.exists() && npmCliJs.exists() ->
            println("Reusing Node.js $version for ${platform.suffix} from ${distributionDirectory.absolutePathString()}")

        else -> {
            distributionDirectory.deleteRecursively()
            distributionDirectory.parent.createDirectories()
            val workDir = createTempDirectory(distributionDirectory.parent, "node-provision")
            try {
                val archive = workDir.resolve(distribution.url.substringAfterLast('/'))
                println("Downloading ${distribution.url}")
                download(distribution.url, archive)
                verifySha256(archive, distribution.sha256.lowercase(Locale.ROOT))

                val extractionRoot = workDir.resolve("extracted")
                println("Extracting Node.js $version for ${platform.suffix}")
                extract(archive, extractionRoot)

                // the node archives wrap everything in a single `node-v<version>-<classifier>` directory, which is
                // stripped so that the configured node/npm paths are relative to the distribution directory
                val extractedRoot = extractionRoot.listDirectoryEntries().singleOrNull()
                    ?: error("Expected a single top-level directory in ${distribution.url}")
                Files.move(extractedRoot, distributionDirectory, StandardCopyOption.ATOMIC_MOVE)
            }
            finally {
                workDir.deleteRecursively()
            }

            check(node.exists()) {
                "Configured node path ${distribution.nodePath} was not found under ${distributionDirectory.absolutePathString()}."
            }
            check(npmCliJs.exists()) {
                "Configured npm-cli.js path ${distribution.npmCliJsPath} was not found under ${distributionDirectory.absolutePathString()}."
            }
            marker.writeText(expectedMarker)
        }
    }

    generatedResourcesDirectory.createDirectories()
    val properties = generatedResourcesDirectory.resolve("node-distribution.properties")
    val expectedProperties = """
        node=${node.absolutePathString()}
        npmCliJs=${npmCliJs.absolutePathString()}
    """.trimIndent()
    // only rewrite when the content actually changes, so that an up-to-date run doesn't retrigger test compilation
    when (expectedProperties) {
        properties.takeIf { it.exists() }?.readText()?.trim() -> Unit
        else -> properties.writeText(expectedProperties)
    }
}

private fun String.resolveUnder(root: Path): Path {
    val relativePath = Path.of(this)
    check(!relativePath.isAbsolute) { "Configured distribution path must be relative: $this" }
    val resolved = root.resolve(relativePath).normalize()
    check(resolved.startsWith(root.normalize())) { "Configured distribution path escapes the distribution directory: $this" }
    return resolved
}
