package org.jetbrains.kmp.resolver.nativeimage.tasks

import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.plugins.*
import org.jetbrains.amper.processes.runProcessWithInheritedIO
import org.jetbrains.kmp.resolver.nativeimage.*
import org.jetbrains.kmp.resolver.nativeimage.models.GraalVmArchive
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.*
import kotlin.io.path.*

private object BuildNativeImageTask {
    val logger: Logger = LoggerFactory.getLogger(this::class.java)
}

@TaskAction
fun buildNativeImage(
    graalVmVersion: String,
    archives: List<GraalVmArchive>,
    @Input applicationJar: CompilationArtifact,
    @Input runtimeClasspath: Classpath,
    mainClass: String,
    @Output outputDirectory: Path,
): Unit = runBlocking {
    val logger = BuildNativeImageTask.logger
    val platform = Platform.current()
    val archive =
        archives.singleOrNull { it.os.normalizedOs() == platform.os && it.arch.normalizedArch() == platform.arch }
            ?: error("No GraalVM Native Image archive configured for ${platform.suffix}.")

    val graalVm = provisionGraalVm(graalVmVersion, archive, platform, logger)

    outputDirectory.createDirectories()
    val outputBinary = outputDirectory.resolve("bazel-kmp-resolver-${platform.suffix}${platform.executableExtension}")
    outputBinary.deleteIfExists()

    val classpath = buildClasspath(applicationJar, runtimeClasspath, platform)
    logger.info("Building ${outputBinary.absolutePathString()} with GraalVM $graalVmVersion")
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
private fun provisionGraalVm(
    graalVmVersion: String,
    archive: GraalVmArchive,
    platform: Platform,
    logger: Logger,
): GraalVmInstallation {
    val cacheRoot = nativeImageCacheRoot()
    val installDir = cacheRoot.resolve("graalvm-$graalVmVersion-${platform.suffix}")
    val marker = installDir.resolve(".native-image-cache")
    val expectedMarker = """
        graalVmVersion=$graalVmVersion
        url=${archive.url}
        sha256=${archive.sha256.lowercase(Locale.ROOT)}
        nativeImagePath=${archive.nativeImagePath}
    """.trimIndent()

    val nativeImagePath = archive.nativeImagePath.resolveUnder(installDir)
    return when {
        marker.exists() && marker.readText().trim() == expectedMarker && nativeImagePath.exists() -> {
            logger.info("Reusing cached GraalVM $graalVmVersion for ${platform.suffix} from ${installDir.absolutePathString()}")
            GraalVmInstallation(home = nativeImagePath.parent.parent, nativeImage = nativeImagePath)
        }

        else -> {
            val archivePath = downloadArchive(cacheRoot, archive, logger)
            installDir.deleteRecursively()
            installDir.createDirectories()
            logger.info("Extracting GraalVM $graalVmVersion for ${platform.suffix}")
            extractArchive(archivePath, installDir, logger)
            marker.writeText(expectedMarker)
            check(nativeImagePath.exists()) {
                "Configured native-image path ${archive.nativeImagePath} was not found under ${installDir.absolutePathString()}."
            }
            GraalVmInstallation(home = nativeImagePath.parent.parent, nativeImage = nativeImagePath)
        }
    }
}

private fun String.resolveUnder(root: Path): Path {
    val relativePath = Path.of(this)
    check(!relativePath.isAbsolute) { "Configured archive path must be relative: $this" }
    val resolved = root.resolve(relativePath).normalize()
    check(resolved.startsWith(root.normalize())) { "Configured archive path escapes extraction directory: $this" }
    return resolved
}

private fun buildClasspath(
    applicationJar: CompilationArtifact, runtimeClasspath: Classpath, platform: Platform
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
