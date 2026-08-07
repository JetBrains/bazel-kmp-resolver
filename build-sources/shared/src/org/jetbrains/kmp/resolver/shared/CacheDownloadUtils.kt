package org.jetbrains.kmp.resolver.shared

import io.ktor.util.collections.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.*

fun kmpResolverCacheRoot(): Path {
    val envPath = System.getenv("BAZEL_KMP_RESOLVER_CACHE_DIR")
    val userHome = Path.of(System.getProperty("user.home"))
    return when {
        !envPath.isNullOrBlank() -> Path.of(envPath)
        else -> when (val os = System.getProperty("os.name").normalizedOs()) {
            "windows" -> {
                val localAppData = System.getenv("LOCALAPPDATA")
                when {
                    localAppData.isNullOrBlank() -> userHome.resolve("AppData").resolve("Local")
                    else -> Path.of(localAppData)
                }
            }

            "macos" -> userHome.resolve("Library").resolve("Caches")
            "linux" -> userHome.resolve(".cache")
            else -> error("Unsupported OS name: $os")
        }.resolve("JetBrains").resolve("bazel-kmp-resolver")
    }
}

sealed class CacheEntry {
    data class Archive(
        val url: String,
        val sha256Checksum: String,
        val location: Path,
    ) : CacheEntry()
}

object ArchiveDownloadCache {
    private val semaphoreByUrl by lazy { ConcurrentMap<String, Semaphore>() }

    @OptIn(ExperimentalPathApi::class)
    suspend fun downloadAndExtract(
        archiveUrl: String,
        archiveSha256Checksum: String,
        destination: Path,
        stripTopLevelFolder: Boolean,
    ): Path = semaphoreByUrl.computeIfAbsent(archiveUrl) { Semaphore(1) }.withPermit {
        withContext(Dispatchers.IO) { // TODO: this is racing
            val marker = destination.resolve("$archiveSha256Checksum.marker")
            when {
                marker.exists() -> destination
                else -> {
                    // @formatter:off
                    val downloadCache = kmpResolverCacheRoot()
                        .resolve("downloads") // used in CI for caching, do not change lightly
                        .resolve(archiveSha256Checksum)
                    // @formatter:on
                    downloadCache.createDirectories()
                    val archiveName = archiveUrl.substringAfterLast("/")
                    val downloaded = httpClient().downloadFile(
                        url = archiveUrl,
                        destination = downloadCache.resolve(archiveName),
                        checksumValidation = ChecksumValidation.UsingHash(
                            expected = archiveSha256Checksum,
                            algorithm = ChecksumAlgorithm.SHA256,
                        ),
                    )
                    val tmp = kmpResolverCacheRoot().resolve("tmp")
                    extractArchive(
                        archive = downloaded,
                        destination = destination,
                        stripTopLevelFolder = stripTopLevelFolder,
                        cleanDestination = true,
                        temporaryDir = tmp,
                    )
                    tmp.deleteRecursively()
                    marker.createFile()
                    destination
                }
            }
        }
    }
}
