package org.jetbrains.kmp.resolver.nativeimage.tasks

import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.plugins.*
import org.jetbrains.amper.processes.runProcessWithInheritedIO
import org.jetbrains.kmp.resolver.shared.ArchiveDownloadCache
import org.jetbrains.kmp.resolver.shared.Platform
import org.jetbrains.kmp.resolver.shared.normalizedArch
import org.jetbrains.kmp.resolver.shared.normalizedOs
import org.jetbrains.kmp.resolver.nativeimage.downloadAndExtractGraalArchive
import org.jetbrains.kmp.resolver.nativeimage.models.GraalVmArchive
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
    val cmd = nativeImageCommand(graalVm.nativeImage, platform) + listOf(
        "--no-fallback",
        "-O3",
        "-cp",
        classpath,
        "-o",
        outputBinary.absolutePathString(),
        mainClass,
    )
    val exitCode = runProcessWithInheritedIO(
        command = cmd,
        environment = mapOf("JAVA_HOME" to graalVm.home.absolutePathString()),
    )
    require(exitCode == 0) { "Command failed with exit code $exitCode: ${cmd.joinToString(" ")}" }
}

private data class GraalVmInstallation(
    val home: Path,
    val nativeImage: Path,
)

@OptIn(ExperimentalPathApi::class)
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

private fun nativeImageCommand(nativeImage: Path, platform: Platform): List<String> = when {
    platform.os == "windows" && nativeImage.name.endsWith(".cmd", ignoreCase = true) -> listOf(
        "cmd.exe", "/c", nativeImage.absolutePathString()
    )

    else -> listOf(nativeImage.absolutePathString())
}
