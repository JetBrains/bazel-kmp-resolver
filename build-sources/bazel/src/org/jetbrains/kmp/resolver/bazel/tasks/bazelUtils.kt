package org.jetbrains.kmp.resolver.bazel.tasks

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.absolutePathString
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import org.jetbrains.amper.processes.ExitCode
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.kmp.resolver.nativeimage.tasks.nativeImageBinaryFileName
import org.jetbrains.kmp.resolver.shared.Platform

/** The staged file the examples' `kmp.resolver(local_* = "//:resolver-local")` tags point to. */
private const val RESOLVER_LOCAL_FILE_NAME = "resolver-local"

/**
 * The resolver binary built by `./kotlin do buildNativeImage`, the binary the staged examples run
 * Bazel with; a failure explaining what to run when it is not built.
 */
internal fun resolverBinary(nativeBinaryDirectory: Path): Path {
    val binary = nativeBinaryDirectory.resolve(nativeImageBinaryFileName(Platform.current()))
    check(binary.isRegularFile()) {
        "The native image ${binary.absolutePathString()} is not built, run './kotlin do buildNativeImage' first"
    }
    return binary
}

/** Stages [binary] into every example workspace, as the file their `kmp.resolver` tags point to. */
internal fun stageResolverIntoExamples(rulesDirectory: Path, binary: Path) {
    val examples = rulesDirectory.resolve("examples")
    val workspaces =
        examples.listDirectoryEntries().sorted().filter {
            it.resolve("MODULE.bazel").isRegularFile()
        }
    check(workspaces.isNotEmpty()) {
        "No example workspace found under ${examples.absolutePathString()}"
    }
    for (example in workspaces) {
        val staged = example.resolve(RESOLVER_LOCAL_FILE_NAME)
        Files.copy(binary, staged, StandardCopyOption.REPLACE_EXISTING)

        // Bazel executes the staged file: keep it executable where the file system supports it.
        if ("posix" in staged.fileSystem.supportedFileAttributeViews()) {
            Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        println("Staged ${binary.absolutePathString()} -> ${staged.absolutePathString()}")
    }
}

/** Runs Bazel in [workingDirectory], failing the task with the output when it exits non-zero. */
internal suspend fun runBazel(workingDirectory: Path, vararg arguments: String) {
    val bazel =
        bazelExecutable()
            ?: error(
                "Neither 'bazel' nor 'bazelisk' found on PATH; install Bazel to build rules_kotlin_external",
            )
    val command = listOf(bazel.absolutePathString()) + arguments
    println("Running ${command.joinToString(" ")} in ${workingDirectory.absolutePathString()}")
    val exitCode =
        runProcess(
                command = command,
                workingDir = workingDirectory,
                outputMode = ProcessOutputMode.Inherit,
                input = ProcessInput.Empty,
            )
            .exitCode
    require(exitCode == ExitCode(0)) {
        "bazel ${arguments.joinToString(" ")} failed with exit code $exitCode, see the output above"
    }
}

private fun bazelExecutable(): Path? {
    val pathDirectories = System.getenv("PATH")?.split(File.pathSeparator) ?: return null
    val suffixes =
        when {
            Platform.current().os == "windows" -> listOf("", ".exe", ".bat")
            else -> listOf("")
        }
    for (name in listOf("bazel", "bazelisk")) {
        for (directory in pathDirectories) {
            for (suffix in suffixes) {
                val executable = Path.of(directory).resolve(name + suffix)
                when {
                    !executable.isRegularFile() -> continue

                    // Windows has no executable bit: a regular file on PATH is enough.
                    Platform.current().os == "windows" -> return executable

                    executable.isExecutable() -> return executable
                }
            }
        }
    }
    return null
}
