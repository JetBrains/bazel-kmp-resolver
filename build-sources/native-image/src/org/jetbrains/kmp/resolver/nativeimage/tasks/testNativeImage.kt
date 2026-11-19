@file:Suppress("BlockingMethodInNonBlockingContext")

package org.jetbrains.kmp.resolver.nativeimage.tasks

import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.amper.processes.ExitCode
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.kmp.resolver.shared.Platform
import org.jetbrains.kmp.resolver.shared.kmpResolverCacheRoot

/**
 * Runs the `generate-bazel-manifest` command of the native image built by `buildNativeImage`, the
 * binary Bazel is going to run, against Maven Central through the JetBrains cache redirector: the
 * CLI parsing, the Amper resolution, the artifact probing and the manifest serialization of the
 * shipped binary are all exercised, and the manifest it writes is compared to a golden file.
 */
@TaskAction
fun testNativeImage(
    @Input nativeBinaryDirectory: Path,
    @Input nodeDistributionPropertiesFile: Path,
    @Input goldenManifestFile: Path,
): Unit = runBlocking {
    val binary = nativeBinaryDirectory.resolve(nativeImageBinaryFileName(Platform.current()))
    check(binary.isRegularFile()) {
        "The native image ${binary.absolutePathString()} is not built, run './kotlin do buildNativeImage' first"
    }
    val nodeDistribution = nodeDistribution(nodeDistributionPropertiesFile)

    // The cache and the manifest are kept under the shared cache root, so reruns reuse the
    // downloaded artifacts and the last manifest stays around to be diffed on a mismatch.
    val workingDir = kmpResolverCacheRoot().resolve("testNativeImage").createDirectories()
    val cacheDir = workingDir.resolve("cache")
    val manifestFile = workingDir.resolve("manifest.json")

    val exitCode =
        withTimeout(5.minutes) {
            runProcess(
                    command =
                        listOf(
                            binary.absolutePathString(),
                            "--coordinate",
                            COROUTINES,
                            "--platform",
                            "ios_arm64",
                            "--platform",
                            "ios_simulator_arm64",
                            "--platform",
                            "wasmjs",
                            "--platform",
                            "jvm",
                            "--repository",
                            MAVEN_CENTRAL,
                            "--cache-dir",
                            cacheDir.absolutePathString(),
                            "--output-manifest-file",
                            manifestFile.absolutePathString(),
                            // resolving the `wasmjs` platform needs a Node.js installation
                            "--node-executable",
                            nodeDistribution.node.absolutePathString(),
                            "--npm-cli-js",
                            nodeDistribution.npmCliJs.absolutePathString(),
                        ),
                    outputMode = ProcessOutputMode.Inherit,
                    input = ProcessInput.Empty,
                )
                .exitCode
        }

    require(exitCode == ExitCode(0)) {
        "the native image failed with exit code $exitCode, see the output above"
    }

    val actualJson = manifestFile.readText().sanitizedLineEndings()
    val expectContent = goldenManifestFile.readText().sanitizedLineEndings()

    if (expectContent != actualJson) {
        /*
        Support a local development flow:
        This is not a typical @Test case, and some IDE goodies are not present.
        Therefore, we allow the user to update the golden manifest file interactively.
         */
        val console = System.console()
        if (console != null) {
            println(
                "The resolved manifest differs from the golden manifest. Do you want to update the manifest? (y/n)",
            )

            if (console.reader().read().toChar() == 'y') {
                goldenManifestFile.writeText(actualJson)
                println("Updated '${goldenManifestFile.toUri()}'")
                return@runBlocking
            }
        }

        error(
            "The manifest the native image wrote ($manifestFile) differs from the golden manifest ($goldenManifestFile)," +
                " diff the two files to see what changed\n\n$actualJson",
        )
    }
}

/**
 * The `node-distribution.properties` file the `provisionNode` task of the `node` plugin writes,
 * pointing at the hermetic Node.js distribution provisioned instead of resolving node/npm from the
 * host.
 */
private data class NodeDistribution(
    val node: Path,
    val npmCliJs: Path,
)

private fun nodeDistribution(propertiesFile: Path): NodeDistribution {
    check(propertiesFile.isRegularFile()) {
        "$propertiesFile does not exist, run './kotlin do provisionNode' to provision the Node.js distribution"
    }
    val properties = Properties()
    propertiesFile.inputStream().use(properties::load)

    fun path(key: String): Path {
        val value = properties.getProperty(key)
        check(value != null) { "Missing `$key` entry in $propertiesFile" }
        val path = Path.of(value)
        check(path.isRegularFile()) {
            "$key does not exist at $path, run './kotlin do provisionNode' to provision it again"
        }
        return path
    }

    return NodeDistribution(
        node = path("node"),
        npmCliJs = path("npmCliJs"),
    )
}

/** Normalizes line endings to LF and removes optional boundary newlines. */
internal fun String.sanitizedLineEndings(): String =
    replace("\r\n", "\n").replace('\r', '\n').removePrefix("\n").removeSuffix("\n")

private const val MAVEN_CENTRAL = "https://cache-redirector.jetbrains.com/repo1.maven.org/maven2"

private const val COROUTINES = "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"
