package org.jetbrains.kmp.resolver.nativeimage

import java.nio.file.Path
import org.jetbrains.kmp.resolver.nativeimage.models.GraalVmArchive
import org.jetbrains.kmp.resolver.shared.ArchiveDownloadCache
import org.jetbrains.kmp.resolver.shared.Platform
import org.jetbrains.kmp.resolver.shared.kmpResolverCacheRoot

internal suspend fun ArchiveDownloadCache.downloadAndExtractGraalArchive(
    archive: GraalVmArchive,
    version: String,
): Path {
    val platform = Platform(archive.os, archive.arch)
    return downloadAndExtract(
        archiveUrl = archive.url,
        archiveSha256Checksum = archive.sha256,
        destination = kmpResolverCacheRoot().resolve("graalvm-$version-${platform.suffix}"),
        stripTopLevelFolder = false,
    )
}
