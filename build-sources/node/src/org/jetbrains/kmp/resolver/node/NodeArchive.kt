package org.jetbrains.kmp.resolver.node

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.io.path.pathString

internal fun download(url: String, destination: Path) {
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    val response = client.send(
        HttpRequest.newBuilder(URI.create(url)).build(),
        HttpResponse.BodyHandlers.ofFile(destination),
    )
    check(response.statusCode() == 200) { "Failed to download $url: HTTP ${response.statusCode()}" }
}

internal fun verifySha256(archive: Path, expected: String) {
    val digest = MessageDigest.getInstance("SHA-256")
    archive.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        generateSequence { input.read(buffer) }.takeWhile { read -> read >= 0 }.forEach { read ->
            digest.update(buffer, 0, read)
        }
    }
    val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    check(actual == expected) { "Checksum mismatch for $archive: expected $expected, got $actual" }
}

internal fun extract(archive: Path, destination: Path) = when {
    archive.pathString.endsWith(".tar.gz") -> extractTarGz(archive, destination)
    archive.pathString.endsWith(".zip") -> extractZip(archive, destination)
    else -> error("Unsupported Node.js archive format: $archive")
}

// the unix node distributions carry symlinks (e.g. bin/npm -> ../lib/node_modules/npm/bin/npm-cli.js)
// and executable bits, both of which must be preserved
private fun extractTarGz(archive: Path, destination: Path) {
    archive.inputStream().buffered().use { input ->
        TarArchiveInputStream(GzipCompressorInputStream(input)).use { tar ->
            generateSequence { tar.nextEntry }.forEach { entry ->
                val target = entryTarget(destination, entry.name)
                when {
                    entry.isDirectory -> target.createDirectories()
                    entry.isSymbolicLink -> {
                        target.parent.createDirectories()
                        Files.createSymbolicLink(target, Path.of(entry.linkName))
                    }

                    else -> {
                        target.parent.createDirectories()
                        target.outputStream().use { output -> tar.copyTo(output) }
                        restorePermissions(target, entry.mode)
                    }
                }
            }
        }
    }
}

// the windows node distribution has neither symlinks nor executable bits, the JVM ZipFile suffices
private fun extractZip(archive: Path, destination: Path) {
    ZipFile(archive.toFile()).use { zip ->
        zip.entries().asSequence().forEach { entry ->
            val target = entryTarget(destination, entry.name)
            when {
                entry.isDirectory -> target.createDirectories()
                else -> {
                    target.parent.createDirectories()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }
}

private fun entryTarget(destination: Path, entryName: String): Path {
    val target = destination.resolve(entryName).normalize()
    require(target.startsWith(destination)) { "Archive entry escapes the extraction destination: $entryName" }
    return target
}

private fun restorePermissions(file: Path, unixMode: Int) = when (unixMode) {
    0 -> Unit
    else -> try {
        Files.setPosixFilePermissions(file, unixMode.toPosixFilePermissions())
    }
    catch (_: UnsupportedOperationException) {
        // non-POSIX file system: nothing to restore (Windows uses the zip distribution anyway)
    }
}

private fun Int.toPosixFilePermissions(): Set<PosixFilePermission> =
    POSIX_PERMISSION_BITS.filter { (bit, _) -> this and bit != 0 }.map { (_, permission) -> permission }.toSet()

private val POSIX_PERMISSION_BITS = listOf(
    0b100000000 to PosixFilePermission.OWNER_READ,
    0b010000000 to PosixFilePermission.OWNER_WRITE,
    0b001000000 to PosixFilePermission.OWNER_EXECUTE,
    0b000100000 to PosixFilePermission.GROUP_READ,
    0b000010000 to PosixFilePermission.GROUP_WRITE,
    0b000001000 to PosixFilePermission.GROUP_EXECUTE,
    0b000000100 to PosixFilePermission.OTHERS_READ,
    0b000000010 to PosixFilePermission.OTHERS_WRITE,
    0b000000001 to PosixFilePermission.OTHERS_EXECUTE,
)
