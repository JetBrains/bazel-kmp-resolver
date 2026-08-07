package org.jetbrains.kmp.resolver.nativeimage

import org.jetbrains.kmp.resolver.nativeimage.models.GraalVmArchive
import org.jetbrains.kmp.resolver.shared.ArchiveDownloadCache
import org.jetbrains.kmp.resolver.shared.CacheEntry
import org.jetbrains.kmp.resolver.shared.Platform
import org.jetbrains.kmp.resolver.shared.kmpResolverCacheRoot
import java.nio.file.Path

internal suspend fun ArchiveDownloadCache.downloadAndExtractGraalArchive(
    archive: GraalVmArchive,
    version: String,
): Path {
    val platform = Platform(archive.os, archive.arch)
    return downloadAndExtract(
        archive = CacheEntry.Archive(
            url = archive.url,
            sha256Checksum = archive.sha256,
            location = kmpResolverCacheRoot().resolve("graalvm-$version-${platform.suffix}"),
        ),
        stripTopLevelFolder = false,
    )
}
