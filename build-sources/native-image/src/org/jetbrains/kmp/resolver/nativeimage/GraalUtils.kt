package org.jetbrains.kmp.resolver.nativeimage

import org.jetbrains.kmp.resolver.nativeimage.models.GraalVmArchive
import org.slf4j.Logger
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.*

internal fun nativeImageCacheRoot(): Path {
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


internal fun downloadArchive(cacheRoot: Path, archive: GraalVmArchive, logger: Logger): Path {
    val downloadsDir = cacheRoot.resolve("downloads")
    downloadsDir.createDirectories()
    val expectedSha = archive.sha256.lowercase()
    val suffix = when {
        archive.url.endsWith(".zip") -> ".zip"
        archive.url.endsWith(".tar.gz") -> ".tar.gz"
        else -> error("unsupported archive extension: ${archive.url}")
    }
    val archivePath = downloadsDir.resolve("$expectedSha$suffix")
    return when {
        archivePath.isRegularFile() && sha256(archivePath.readBytesForSha256()) == expectedSha -> archivePath
        else -> {
            archivePath.deleteIfExists()
            val tempArchive = createTempFile(directory = downloadsDir, prefix = "$expectedSha-", suffix = ".tmp")
            logger.info("Downloading ${archive.url}")
            try {
                URI(archive.url).toURL().openStream().use { input ->
                    tempArchive.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val actualSha = sha256(tempArchive.readBytesForSha256())
                check(actualSha == expectedSha) {
                    "Checksum mismatch for ${archive.url}: expected ${archive.sha256}, got $actualSha"
                }
                tempArchive.moveTo(archivePath, overwrite = true)
            } finally {
                tempArchive.deleteIfExists()
            }
            archivePath
        }
    }
}

internal fun extractArchive(archivePath: Path, destination: Path, logger: Logger) {
    val tempDir = createTempFile("extracted")
    when {
        archivePath.extension == "zip" -> extractZip(
            archive = archivePath,
            destination = destination,
            stripTopLevelFolder = false,
            cleanDestination = true,
            logger = logger,
            temporaryDir = tempDir
        )

        archivePath.pathString.endsWith(".tar.gz") -> extractTarGz(
            archive = archivePath,
            destination = destination,
            stripTopLevelFolder = false,
            cleanDestination = true,
            logger = logger,
            temporaryDir = tempDir
        )

        else -> error("Unsupported GraalVM archive format: ${archivePath.absolutePathString()}")
    }
}