package org.jetbrains.kmp.resolver.bazel.tasks

import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.isRegularFile
import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.amper.processes.ExitCode
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.kmp.resolver.shared.Platform

/**
 * Runs the Bazel tests of rules_kotlin_external against the freshly built resolver: the task graph
 * builds the native image first — the [nativeBinaryDirectory] input is the `buildNativeImage`
 * task's output directory, wiring it as a dependency, incrementally cached — then it is staged into
 * the example workspaces, the Starlark unit tests of the rules run, and the facts caching behavior
 * of the `kmp` module extension (fresh resolution, cache hit after `bazel clean --expunge`,
 * re-resolution after a definition change).
 *
 * Execution avoidance is disabled: the task verifies the current state of the rules and the
 * examples, Bazel itself caches whatever did not change.
 */
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun testBazel(
    @Input nativeBinaryDirectory: Path,
    @Input rulesDirectory: Path,
): Unit = runBlocking {
    val binary = resolverBinary(nativeBinaryDirectory)
    stageResolverIntoExamples(rulesDirectory, binary)

    runBazel(rulesDirectory, "test", "//tests/...")

    val factsBehaviorScript = rulesDirectory.resolve("examples/facts-behavior.sh")
    check(factsBehaviorScript.isRegularFile()) {
        "The facts behavior script ${factsBehaviorScript.absolutePathString()} does not exist"
    }
    val command =
        when {
            // Windows executes shell scripts through bash, when installed.
            Platform.current().os == "windows" ->
                listOf("bash", factsBehaviorScript.absolutePathString())

            else -> listOf(factsBehaviorScript.absolutePathString())
        }
    println("Running ${command.joinToString(" ")}")
    val exitCode =
        runProcess(
                command = command,
                outputMode = ProcessOutputMode.Inherit,
                input = ProcessInput.Empty,
            )
            .exitCode
    require(exitCode == ExitCode(0)) {
        "The facts caching behavior of the kmp extension failed with exit code $exitCode, see the output above"
    }
}
