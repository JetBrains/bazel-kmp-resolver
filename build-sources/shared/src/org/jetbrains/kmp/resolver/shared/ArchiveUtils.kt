package org.jetbrains.kmp.resolver.shared

import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.InputStream
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipInputStream
import kotlin.io.path.*

fun extractArchive(
    archive: Path,
    destination: Path,
    stripTopLevelFolder: Boolean,
    cleanDestination: Boolean,
    temporaryDir: Path,
) = when {
    // @formatter:off
    archive.name.endsWith(".tar.gz") -> extractTarGz(archive, destination, stripTopLevelFolder, cleanDestination, temporaryDir )
    archive.extension == "zip" -> extractZip(archive, destination, stripTopLevelFolder, cleanDestination, temporaryDir)
    else -> error("Unsupported archive extension: ${archive.name}")
    // @formatter:on
}

/**
 * Extracts a ZIP archive to a specified output path. Output files will preserve symlinks and file permissions from an archive.
 *
 * Warning: to preserve necessary meta-data, we use [ZipFile] instead of [ZipInputStream] or [ZipArchiveInputStream],
 * due to limitations of latter ([source](https://stackoverflow.com/questions/20220256/unzip-symlinks-from-a-ziparchiveinputstream-on-android)).
 * This might result in performance drawbacks. Also, it's not recommended to use this method to extract large archives.
 * Please use it with caution.
 */
fun extractZip(
    archive: Path,
    destination: Path,
    stripTopLevelFolder: Boolean,
    cleanDestination: Boolean,
    temporaryDir: Path,
) = extract(archive, destination, stripTopLevelFolder, cleanDestination, ArchiveType.ZIP, "", temporaryDir)


fun extractTarGz(
    archive: Path,
    destination: Path,
    stripTopLevelFolder: Boolean,
    cleanDestination: Boolean,
    temporaryDir: Path,
    encoding: String? = null,
) = extractCompressedTar(
    archive, destination, stripTopLevelFolder, cleanDestination, CompressorStreamFactory.GZIP, temporaryDir, encoding
)

private fun extractCompressedTar(
    archive: Path,
    destination: Path,
    stripTopLevelFolder: Boolean,
    cleanDestination: Boolean,
    compressorName: String,
    temporaryDir: Path,
    encoding: String? = null,
) = extract(
    archive, destination, stripTopLevelFolder, cleanDestination, ArchiveType.TAR, compressorName, temporaryDir, encoding
)

@OptIn(ExperimentalPathApi::class)
private fun extract(
    archive: Path,
    destination: Path,
    stripTopLevelFolder: Boolean,
    cleanDestination: Boolean,
    archiveType: ArchiveType,
    compressorName: String,
    temporaryDir: Path,
    encoding: String? = null,
) {
    val tmpFolder = temporaryDir.resolve("${archive.fileName}_extracted")
    tmpFolder.deleteRecursively()
    tmpFolder.createDirectories()
    println("Extracting '$archive' to '$tmpFolder'")

    when (archiveType) {
        ArchiveType.ZIP -> {
            ZipFile.builder().setPath(archive).get().use { zipFile ->
                zipFile.entries.asSequence().forEach { entry ->
                    extractEntry(
                        entry = entry,
                        destination = tmpFolder,
                        stripTopLevelFolder = stripTopLevelFolder,
                        isSymbolicLink = entry.isUnixSymlink,
                        unixMode = entry.unixMode,
                        symLink = zipFile.getUnixSymlink(entry),
                        entryInputStreamProducer = { zipFile.getInputStream(entry).buffered() },
                    )
                }
            }
        }

        ArchiveType.TAR -> archive.inputStream().buffered().use { bufferedInputStream ->
            when (compressorName) {
                CompressorStreamFactory.ZSTANDARD -> ZstdCompressorInputStream(bufferedInputStream)
                CompressorStreamFactory.XZ -> XZCompressorInputStream(bufferedInputStream)
                else -> CompressorStreamFactory().createCompressorInputStream(compressorName, bufferedInputStream)
            }.use { `in` ->
                TarArchiveInputStream(`in`, encoding).use { archiveInputStream ->
                    archiveInputStream.extractEntriesTo(stripTopLevelFolder, tmpFolder)
                }
            }
        }
    }
    println("Extracted '$archive' to '$tmpFolder'")
    println("Moving '$tmpFolder' to '$destination'")
    when {
        cleanDestination -> destination.deleteRecursively()
        else -> require(!destination.exists() || destination.isDirectory()) { "destination be a directory is it exists" }
    }
    destination.parent.createDirectories()
    tmpFolder.copyToRecursively(destination, followLinks = false, overwrite = cleanDestination)
    tmpFolder.deleteRecursively()
    println("Moved '$tmpFolder' to '$destination'")
}


private fun TarArchiveInputStream.extractEntriesTo(stripTopLevelFolder: Boolean, destination: Path) {
    val archiveInputStream = this
    var entry = archiveInputStream.nextEntry
    while (entry != null) {
        extractEntry(
            entry = entry,
            destination = destination,
            stripTopLevelFolder = stripTopLevelFolder,
            isSymbolicLink = entry.isSymbolicLink,
            symLink = entry.linkName,
            unixMode = entry.mode,
        ) { archiveInputStream }
        entry = archiveInputStream.nextEntry
    }
}

/**
 * Extracts a given archive entry to a specified destination path, applying optional transformations
 * such as stripping the top-level folder, handling symbolic links, and transferring file permissions.
 */
private fun extractEntry(
    entry: ArchiveEntry, destination: Path,
    stripTopLevelFolder: Boolean,
    isSymbolicLink: Boolean,
    symLink: String?,
    unixMode: Int,
    entryInputStreamProducer: () -> InputStream,
) {
    val relative = when {
        stripTopLevelFolder -> entry.name.split("/").drop(1).joinToString("/")
        else -> entry.name
    }
    if (relative != "") {
        val destinationFile = destination.resolve(relative).normalize()
        val parent = destinationFile.parent
        val realParent = when {
            parent.exists(LinkOption.NOFOLLOW_LINKS) && parent.isSymbolicLink() -> parent.parent.resolve(parent.readSymbolicLink())
            else -> parent
        }
        realParent.createDirectories()

        when {
            entry.isDirectory -> if (!destinationFile.exists()) {
                destinationFile.createDirectories()
            }

            isSymbolicLink -> {
                requireNotNull(symLink) { "symLink must not be null when isSymbolicLink is true" }
                destinationFile.createSymbolicLinkPointingTo(Path.of(symLink))
            }

            else -> destinationFile.outputStream().use { fileOut ->
                entryInputStreamProducer().copyTo(fileOut)
            }
        }

        restorePermissions(destinationFile, unixMode, isSymbolicLink)
    }
}

private fun restorePermissions(destinationFile: Path, unixMode: Int, isSymbolicLink: Boolean) {
    when {
        isSymbolicLink -> {}
        unixMode == 0 -> {}
        else -> try {
            val attr = destinationFile.fileAttributesView<PosixFileAttributeView>(LinkOption.NOFOLLOW_LINKS)
            attr.setPermissions(unixMode.toPosixPermissions())
        } catch (_: UnsupportedOperationException) {
            println("Could not restore permissions on $destinationFile, file system is not POSIX compliant")
        }
    }
}


internal fun Int.toPosixPermissions(): Set<PosixFilePermission> {
    val perms = mutableSetOf<PosixFilePermission>()
    if (this and 0b100000000 != 0) perms.add(PosixFilePermission.OWNER_READ)
    if (this and 0b010000000 != 0) perms.add(PosixFilePermission.OWNER_WRITE)
    if (this and 0b001000000 != 0) perms.add(PosixFilePermission.OWNER_EXECUTE)
    if (this and 0b000100000 != 0) perms.add(PosixFilePermission.GROUP_READ)
    if (this and 0b000010000 != 0) perms.add(PosixFilePermission.GROUP_WRITE)
    if (this and 0b000001000 != 0) perms.add(PosixFilePermission.GROUP_EXECUTE)
    if (this and 0b000000100 != 0) perms.add(PosixFilePermission.OTHERS_READ)
    if (this and 0b000000010 != 0) perms.add(PosixFilePermission.OTHERS_WRITE)
    if (this and 0b000000001 != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE)
    return perms
}

private enum class ArchiveType {
    ZIP, TAR,
}
