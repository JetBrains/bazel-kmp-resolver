package org.jetbrains.kmp.resolver.nativeimage.tasks

import org.jetbrains.amper.plugins.*
import org.jetbrains.kmp.resolver.nativeimage.downloadArchive
import org.jetbrains.kmp.resolver.nativeimage.models.GraalVmArchive
import org.jetbrains.kmp.resolver.nativeimage.nativeImageCacheRoot
import kotlin.io.path.*

@TaskAction
fun cacheGraalVmArchives(
    graalVmVersion: String,
    archives: List<GraalVmArchive>,
) {
    val cacheRoot = nativeImageCacheRoot()
    archives.forEach { archive -> downloadArchive(cacheRoot, archive) }
    println(
        "Cached ${archives.size} GraalVM Native Image archives for $graalVmVersion under ${
            cacheRoot.resolve("downloads").absolutePathString()
        }"
    )
}
