package org.jetbrains.kmp.resolver.shared

import io.ktor.util.collections.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.encoding.Base64
import kotlin.io.path.*

typealias CacheKey = String

sealed class CacheEntry {
    abstract val key: CacheKey

    data class Archive(
        val url: String,
        val sha256Checksum: String,
        val location: Path,
    ) : CacheEntry() {
        override val key: CacheKey = "${sha256Checksum}_${Base64.encode(location.absolutePathString().toByteArray())}"
    }
}

object ArchiveDownloadCache {
    private val semaphoreByUrl by lazy { ConcurrentMap<String, Semaphore>() }

    @OptIn(ExperimentalPathApi::class)
    suspend fun downloadAndExtract(
        archive: CacheEntry.Archive,
        stripTopLevelFolder: Boolean,
        temporaryDir: Path,
    ): Path = semaphoreByUrl.computeIfAbsent(archive.url) { Semaphore(1) }.withPermit {
        withContext(Dispatchers.IO) { // TODO: this is racing
            val marker = archive.location.resolveSibling(archive.key)
            when {
                marker.exists() -> archive.location
                else -> {
                    val tmp = temporaryDir.resolve(archive.key)
                    tmp.createDirectories()
                    val archiveName = archive.url.substringAfterLast("/")
                    val downloaded = httpClient().downloadFile(
                        url = archive.url,
                        destination = tmp.resolve(archiveName),
                        checksumValidation = ChecksumValidation.UsingHash(
                            expected = archive.sha256Checksum,
                            algorithm = ChecksumAlgorithm.SHA256,
                        ),
                    )
                    extractArchive(
                        archive = downloaded,
                        destination = archive.location,
                        stripTopLevelFolder = stripTopLevelFolder,
                        cleanDestination = true,
                        temporaryDir = tmp,
                    )
                    downloaded.deleteIfExists()
                    marker.createFile()
                    archive.location
                }
            }
        }
    }
}
