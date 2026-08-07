package org.jetbrains.kmp.resolver.node.tasks

import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.kmp.resolver.node.models.NodeDistributionArchive
import org.jetbrains.kmp.resolver.shared.*
import java.nio.file.Path
import kotlin.io.path.*

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
): Unit = runBlocking {
    val platform = Platform.current()
    val distribution =
        distributions.singleOrNull { it.os.normalizedOs() == platform.os && it.arch.normalizedArch() == platform.arch }
            ?: error(
                "No Node.js distribution configured for ${platform.suffix}. Configured platforms: " + distributions.joinToString { "${it.os}-${it.arch}" })

    distributionDirectory.createDirectories()
    val extracted = ArchiveDownloadCache.downloadAndExtract(
        archive = CacheEntry.Archive(
            url = distribution.url,
            sha256Checksum = distribution.sha256,
            location = distributionDirectory,
        ),
        stripTopLevelFolder = true,
        temporaryDir = createTempDirectory("tmp_download_cache"),
    )

    val node = extracted.resolve(distribution.nodePath)
    check(node.exists()) {
        "Configured node path ${distribution.nodePath} was not found under ${distributionDirectory.absolutePathString()}."
    }

    val npmCliJs = extracted.resolve(distribution.npmCliJsPath)
    check(npmCliJs.exists()) {
        "Configured npm-cli.js path ${distribution.npmCliJsPath} was not found under ${distributionDirectory.absolutePathString()}."
    }

    generatedResourcesDirectory.createDirectories()
    generatedResourcesDirectory.resolve("node-distribution.properties").writeText(
        """
            node=${node.absolutePathString()}
            npmCliJs=${npmCliJs.absolutePathString()}
        """.trimIndent()
    )
}
