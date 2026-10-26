package org.jetbrains.kmp.resolver.auth

import java.nio.file.Path
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

internal class MutableClock(private var now: Instant) : Clock {
    override fun now(): Instant = now

    fun advance(duration: Duration) {
        now += duration
    }
}

private val isWindows = System.getProperty("os.name").startsWith("Windows")

/**
 * Writes an executable credential helper at `directory/name` which appends its standard input
 * followed by a newline to [requestLog], prints [stderr] to its standard error when not empty,
 * waits for [delay], prints [stdout] and exits with [exitCode]. Returns its path.
 */
internal fun writeFakeCredentialHelper(
    directory: Path,
    name: String,
    stdout: String,
    stderr: String,
    exitCode: Int,
    delay: Duration,
    requestLog: Path,
): Path {
    require('\'' !in stdout && '\'' !in stderr) { "quotes are not supported in fake helper output" }
    return when {
        isWindows ->
            directory.resolve("$name.cmd").apply {
                writeText(
                    buildString {
                        appendLine("@echo off")
                        appendLine("findstr \"^\" >> \"$requestLog\"")
                        appendLine("echo.>> \"$requestLog\"")
                        if (stderr.isNotEmpty()) appendLine("echo $stderr 1>&2")
                        if (delay.isPositive())
                            appendLine("ping -n ${delay.inWholeSeconds + 1} 127.0.0.1 >nul")
                        appendLine("echo $stdout")
                        appendLine("exit /b $exitCode")
                    },
                )
            }

        else ->
            directory.resolve("$name.sh").apply {
                writeText(
                    buildString {
                        appendLine("#!/bin/sh")
                        appendLine("cat >> '$requestLog'")
                        appendLine("printf '\\n' >> '$requestLog'")
                        if (stderr.isNotEmpty()) appendLine("printf '%s\\n' '$stderr' >&2")
                        if (delay.isPositive())
                            appendLine("sleep ${delay.inWholeMilliseconds / 1000.0}")
                        appendLine("printf '%s' '$stdout'")
                        appendLine("exit $exitCode")
                    },
                )
                setPosixFilePermissions(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"),
                )
            }
    }
}
