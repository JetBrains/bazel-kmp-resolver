package org.jetbrains.kmp.resolver.nativeimage

import org.jetbrains.kmp.resolver.shared.ArchiveDownloadCache
import org.jetbrains.kmp.resolver.shared.CacheEntry
import org.jetbrains.kmp.resolver.shared.Platform
import org.jetbrains.kmp.resolver.shared.normalizedOs
import org.jetbrains.kmp.resolver.nativeimage.models.GraalVmArchive
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

internal suspend fun ArchiveDownloadCache.downloadAndExtractGraalArchive(
    archive: GraalVmArchive,
    version: String,
): Path {
    val platform = Platform(archive.os, archive.arch)
    return downloadAndExtract(
        archive = CacheEntry.Archive(
            url = archive.url,
            sha256Checksum = archive.sha256,
            location = graalDistributionCacheEntry(version, platform),
        ),
        stripTopLevelFolder = false,
        temporaryDir = createTempDirectory("tmp_download_cache"),
    )
}

private fun graalDistributionCacheEntry(
    graalVmVersion: String,
    platform: Platform,
): Path = nativeImageCacheRoot().resolve("graalvm-$graalVmVersion-${platform.suffix}")

private fun nativeImageCacheRoot(): Path {
    val envPath = System.getenv("GRAALVM_NATIVE_IMAGE_CACHE_DIR")
    val userHome = Path.of(System.getProperty("user.home"))
    return when {
        !envPath.isNullOrBlank() -> Path.of(envPath)
        System.getProperty("os.name").normalizedOs() == "windows" -> {
            val localAppData = System.getenv("LOCALAPPDATA")
            val base = when {
                localAppData.isNullOrBlank() -> userHome.resolve("AppData").resolve("Local")
                else -> Path.of(localAppData)
            }
            base.resolve("bazel-kmp-resolver").resolve("native-image")
        }

        else -> userHome.resolve(".cache").resolve("bazel-kmp-resolver").resolve("native-image")
    }
}
