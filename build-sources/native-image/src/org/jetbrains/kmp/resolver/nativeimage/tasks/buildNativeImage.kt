package org.jetbrains.kmp.resolver.nativeimage.tasks

import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.plugins.*
import org.jetbrains.amper.processes.ExitCode
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.kmp.resolver.nativeimage.downloadAndExtractGraalArchive
import org.jetbrains.kmp.resolver.nativeimage.models.GraalVmArchive
import org.jetbrains.kmp.resolver.shared.ArchiveDownloadCache
import org.jetbrains.kmp.resolver.shared.Platform
import org.jetbrains.kmp.resolver.shared.normalizedArch
import org.jetbrains.kmp.resolver.shared.normalizedOs

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
        archives.singleOrNull {
            it.os.normalizedOs() == platform.os && it.arch.normalizedArch() == platform.arch
        } ?: error("No GraalVM Native Image archive configured for ${platform.suffix}.")

    val graalVm = provisionGraalVm(graalVmVersion, archive)

    outputDirectory.createDirectories()
    val outputBinary = outputDirectory.resolve(nativeImageBinaryFileName(platform))
    outputBinary.deleteIfExists()

    val classpath = buildClasspath(applicationJar, runtimeClasspath, platform)
    println("Building ${outputBinary.absolutePathString()} with GraalVM $graalVmVersion")

    // The classpath alone can exceed the 8191-character command line limit of cmd.exe on Windows,
    // so all arguments go through an @argfile regardless of the platform.
    val argFile =
        writeNativeImageArgfile(
            targetArgfile = outputDirectory.resolve("native-image-${platform.suffix}.args"),
            classpath = classpath,
            /* Provide the output binary by name (without extension): Graal handles the extension internally */
            outputBinary =
                outputDirectory.resolve(nativeImageBinaryName(platform)).absolutePathString(),
            mainClass = mainClass,
        )
    val cmd = nativeImageCommand(graalVm.nativeImage, platform, argFile)
    val exitCode =
        runProcess(
                command = cmd,
                configureEnvironment = { put("JAVA_HOME", graalVm.home.absolutePathString()) },
                outputMode = ProcessOutputMode.Inherit,
                input = ProcessInput.Empty,
            )
            .exitCode
    require(exitCode == ExitCode(0)) {
        """
Command failed with exit code $exitCode: ${cmd.joinToString(" ")}

ArgFile '$argFile' content:
${argFile.readLines().joinToString("\n")}
        """
    }
}

/**
 * Quotes an argument for a native-image @argfile. The driver tokenizes such files like the `java`
 * launcher does: within double quotes, a backslash escapes the next character, so backslashes
 * (Windows path separators) and double quotes must be escaped.
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
    }
        .distinct()
        .map { it.absolutePathString() }
        .toList()

    return classpathFiles.joinToString(platform.classpathSeparator)
}

private fun nativeImageCommand(nativeImage: Path, platform: Platform, argFile: Path): List<String> =
    when {
        platform.os == "windows" && nativeImage.name.endsWith(".cmd", ignoreCase = true) ->
            listOf(
                "cmd.exe",
                "/c",
                nativeImage.absolutePathString(),
                "@${argFile.absolutePathString()}",
            )

        else ->
            listOf(
                nativeImage.absolutePathString(),
                "@${argFile.absolutePathString()}",
            )
    }

private fun writeNativeImageArgfile(
    targetArgfile: Path,
    classpath: String,
    outputBinary: String,
    mainClass: String,
): Path {
    val nativeImageArgs =
        listOf(
            "--no-fallback",
            "-O3",
            "-cp",
            classpath,
            "-o",
            outputBinary,
            mainClass,
        )
    targetArgfile.writeText(nativeImageArgs.joinToString("\n") { it.quoteForArgFile() })
    return targetArgfile
}

fun nativeImageBinaryName(platform: Platform): String = "bazel-kmp-resolver-${platform.suffix}"

fun nativeImageBinaryFileName(platform: Platform): String =
    when (platform.os) {
        "windows" -> nativeImageBinaryName(platform) + ".exe"
        else -> nativeImageBinaryName(platform)
    }
