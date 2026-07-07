package org.jetbrains.kmp.resolver.nativeimage.tasks

import org.jetbrains.amper.plugins.*
import org.jetbrains.kmp.resolver.nativeimage.downloadArchive
import org.jetbrains.kmp.resolver.nativeimage.models.GraalVmArchive
import org.jetbrains.kmp.resolver.nativeimage.nativeImageCacheRoot
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.io.path.*

private object CacheGraalVmNativeArchivesTask {
    val logger: Logger = LoggerFactory.getLogger(this::class.java)
}

@TaskAction
fun cacheGraalVmArchives(
    graalVmVersion: String,
    archives: List<GraalVmArchive>,
) {
    val logger = CacheGraalVmNativeArchivesTask.logger
    val cacheRoot = nativeImageCacheRoot()
    archives.forEach { archive -> downloadArchive(cacheRoot, archive, logger) }
    logger.info(
        "Cached ${archives.size} GraalVM Native Image archives for $graalVmVersion under ${
            cacheRoot.resolve("downloads").absolutePathString()
        }"
    )
}
