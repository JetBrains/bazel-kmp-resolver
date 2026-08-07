package org.jetbrains.kmp.resolver.nativeimage.tasks

import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.kmp.resolver.nativeimage.downloadAndExtractGraalArchive
import org.jetbrains.kmp.resolver.nativeimage.models.GraalVmArchive
import org.jetbrains.kmp.resolver.shared.ArchiveDownloadCache

@TaskAction
fun cacheGraalVmArchives(
    graalVmVersion: String,
    archives: List<GraalVmArchive>,
): Unit = runBlocking {
    archives.forEach { archive -> ArchiveDownloadCache.downloadAndExtractGraalArchive(archive, graalVmVersion) }
    println("Cached ${archives.size} GraalVM Native Image archives for $graalVmVersion.")
}
