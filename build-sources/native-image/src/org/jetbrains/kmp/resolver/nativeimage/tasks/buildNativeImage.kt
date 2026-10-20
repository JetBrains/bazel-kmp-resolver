package org.jetbrains.kmp.resolver.nativeimage.tasks

import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.plugins.*
import org.jetbrains.amper.processes.runProcessWithInheritedIO
import org.jetbrains.kmp.resolver.nativeimage.downloadAndExtractGraalArchive
import org.jetbrains.kmp.resolver.nativeimage.models.GraalVmArchive
import org.jetbrains.kmp.resolver.shared.ArchiveDownloadCache
import org.jetbrains.kmp.resolver.shared.Platform
import org.jetbrains.kmp.resolver.shared.normalizedArch
import org.jetbrains.kmp.resolver.shared.normalizedOs
import java.nio.file.Path
import kotlin.io.path.*

@TaskAction
fun buildNativeImage(
    graalVmVersion: String,
    archives: List<GraalVmArchive>,
    @Input applicationJar: CompilationArtifact,
    @Input runtimeClasspath: Classpath,
    mainClass: String,
    @Output outputDirectory: Path,
): Unit = runBlocking {
    val platform = Platform.current()
    val archive =
        archives.singleOrNull { it.os.normalizedOs() == platform.os && it.arch.normalizedArch() == platform.arch }
            ?: error("No GraalVM Native Image archive configured for ${platform.suffix}.")

    val graalVm = provisionGraalVm(graalVmVersion, archive)

    outputDirectory.createDirectories()
    val outputBinary =
        outputDirectory.resolve("bazel-kmp-resolver-${platform.suffix}") // no platform suffix, Graal handles that internally
    outputBinary.deleteIfExists()

    val classpath = buildClasspath(applicationJar, runtimeClasspath, platform)
    println("Building ${outputBinary.absolutePathString()} with GraalVM $graalVmVersion")

    // The classpath alone can exceed the 8191-character command line limit of cmd.exe on Windows,
    // so all arguments go through an @argfile regardless of the platform.
    val argFile = writeNativeImageArgfile(
        targetArgfile = outputDirectory.resolve("native-image-${platform.suffix}.args"),
        classpath = classpath,
        outputBinary = outputBinary,
        mainClass = mainClass,
    )
    val cmd = nativeImageCommand(graalVm.nativeImage, platform, argFile)
    val exitCode = runProcessWithInheritedIO(
        command = cmd,
        environment = mapOf("JAVA_HOME" to graalVm.home.absolutePathString()),
    )
    require(exitCode == 0) {
        """
Command failed with exit code $exitCode: ${cmd.joinToString(" ")}

ArgFile '$argFile' content:
${argFile.readLines().joinToString("\n")}
        """
    }
}

/**
 * Quotes an argument for a native-image @argfile. The driver tokenizes such files like the `java` launcher does:
 * within double quotes, a backslash escapes the next character, so backslashes (Windows path separators) and
 * double quotes must be escaped.
 */
private fun String.quoteForArgFile(): String = buildString {
    append("\"")
    append(replace("\\", "\\\\").replace("\"", "\\\""))
    append("\"")
}

private data class GraalVmInstallation(
    val home: Path,
    val nativeImage: Path,
)

private suspend fun provisionGraalVm(
    graalVmVersion: String,
    archive: GraalVmArchive,
): GraalVmInstallation {
    val extracted = ArchiveDownloadCache.downloadAndExtractGraalArchive(archive, graalVmVersion)
    val nativeImagePath = extracted.resolve(archive.nativeImagePath)
    check(nativeImagePath.exists()) {
        "Configured native-image path ${archive.nativeImagePath} was not found under ${extracted.absolutePathString()}."
    }
    return GraalVmInstallation(home = nativeImagePath.parent.parent, nativeImage = nativeImagePath)
}

private fun buildClasspath(
    applicationJar: CompilationArtifact,
    runtimeClasspath: Classpath,
    platform: Platform,
): String {
    val classpathFiles = sequence {
        yield(applicationJar.artifact)
        yieldAll(runtimeClasspath.resolvedFiles)
    }.distinct().map { it.absolutePathString() }.toList()

    return classpathFiles.joinToString(platform.classpathSeparator)
}

private fun nativeImageCommand(nativeImage: Path, platform: Platform, argFile: Path): List<String> = when {
    platform.os == "windows" && nativeImage.name.endsWith(".cmd", ignoreCase = true) -> listOf(
        "cmd.exe",
        "/c",
        nativeImage.absolutePathString(),
        "@${argFile.absolutePathString()}",
    )

    else -> listOf(
        nativeImage.absolutePathString(),
        "@${argFile.absolutePathString()}",
    )
}

private fun writeNativeImageArgfile(
    targetArgfile: Path,
    classpath: String,
    outputBinary: Path,
    mainClass: String,
): Path {
    val nativeImageArgs = listOf(
        "--no-fallback",
        "-O3",
        "-cp",
        classpath,
        "-o",
        outputBinary.absolutePathString(),
        mainClass,
    )
    targetArgfile.writeText(nativeImageArgs.joinToString("\n") { it.quoteForArgFile() })
    return targetArgfile
}
