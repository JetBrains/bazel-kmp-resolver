package org.jetbrains.kmp.resolver.format.tasks

import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.amper.processes.runProcessWithInheritedIO
import org.jetbrains.kmp.resolver.format.models.KtfmtDistribution
import org.jetbrains.kmp.resolver.shared.*

/**
 * Formats the Kotlin sources of [directories] in place with ktfmt's kotlinlang style, running the
 * pinned native [ktfmt] binary of the current platform, downloaded once into the resolver's
 * download cache. Execution avoidance is disabled: formatting rewrites its own inputs, so the task
 * runs every time it is asked to.
 */
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun format(
    ktfmt: KtfmtDistribution,
    @Input directories: List<Path>,
): Unit = runBlocking {
    val existing = directories.filter { it.exists() }
    val missing = directories - existing.toSet()
    if (missing.isNotEmpty())
        println("Skipping missing directories: ${missing.joinToString { it.absolutePathString() }}")

    val executable = provisionKtfmt(ktfmt)
    val command = buildList {
        add(executable.absolutePathString())
        add("--kotlinlang-style")
        existing.forEach { add(it.absolutePathString()) }
    }
    println(
        "Formatting with ktfmt ${ktfmt.version}: ${existing.joinToString { it.absolutePathString() }}",
    )
    // ktfmt occasionally reaches its fixed point only on a second pass (a line it wraps on the
    // first pass gets a trailing comma it then wraps differently), and the CI check requires a
    // clean tree after one `format`.
    repeat(2) {
        val exitCode = runProcessWithInheritedIO(command = command)
        require(exitCode == 0) {
            "ktfmt failed with exit code $exitCode: ${command.joinToString(" ")}"
        }
    }
}

private suspend fun provisionKtfmt(ktfmt: KtfmtDistribution): Path {
    val platform = Platform.current()
    val archive =
        ktfmt.archives.singleOrNull {
            it.os.normalizedOs() == platform.os && it.arch.normalizedArch() == platform.arch
        }
            ?: error(
                """ktfmt ${ktfmt.version} has no native binary configured for ${platform.suffix}, formatting is not possible on this platform.
                    |Configured platforms: ${ktfmt.archives.joinToString { "${it.os}-${it.arch}" }}"""
                    .trimMargin(),
            )
    val extracted =
        ArchiveDownloadCache.downloadAndExtract(
            archiveUrl = archive.url,
            archiveSha256Checksum = archive.sha256,
            destination =
                kmpResolverCacheRoot().resolve("ktfmt-${ktfmt.version}-${platform.suffix}"),
            stripTopLevelFolder = false,
        )
    val executable = extracted.resolve(archive.executablePath)
    check(executable.exists()) {
        "Configured ktfmt executable path ${archive.executablePath} was not found under ${extracted.absolutePathString()}."
    }
    if (platform.os != "windows" && !executable.isExecutable())
        executable.toFile().setExecutable(true)
    return executable
}
