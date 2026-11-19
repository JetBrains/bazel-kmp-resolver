package org.jetbrains.kmp.resolver.gradlegoldendata.tasks

import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
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
 * Regenerates the golden data the resolver tests compare against: runs the `resolveDependencies`
 * task of the checked-in Gradle test projects in [gradleProjectsDirectory] with their own Gradle
 * wrapper, which writes the resolved artifact graphs and hashes into their 'golden-data'
 * directories. Execution avoidance is disabled: the task rewrites its own inputs (the golden data),
 * so it runs every time it is asked to.
 */
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun updateGradleGoldenData(
    @Input gradleProjectsDirectory: Path,
): Unit = runBlocking {
    check(gradleProjectsDirectory.exists()) {
        "The Gradle test projects directory ${gradleProjectsDirectory.absolutePathString()} does not exist."
    }
    val gradleWrapper =
        gradleProjectsDirectory.resolve(
            when (Platform.current().os) {
                "windows" -> "gradlew.bat"
                else -> "gradlew"
            },
        )
    check(gradleWrapper.isExecutable()) {
        "The Gradle wrapper ${gradleWrapper.absolutePathString()} is missing or not executable."
    }
    val exitCode =
        runProcess(
                workingDir = gradleProjectsDirectory,
                command = listOf(gradleWrapper.absolutePathString(), "resolveDependencies"),
                outputMode = ProcessOutputMode.Inherit,
                input = ProcessInput.Empty,
            )
            .exitCode
    require(exitCode == ExitCode(0)) {
        "'gradlew resolveDependencies' failed with exit code $exitCode, see the output above"
    }
}
