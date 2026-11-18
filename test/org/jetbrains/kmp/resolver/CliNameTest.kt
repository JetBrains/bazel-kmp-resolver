package org.jetbrains.kmp.resolver

import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test

class CliNameTest {

    /**
     * Lock the cliName of supported resolution platforms (to protect against accidental changes)
     */
    @Test
    fun `compare cliName json`() {
        val expectFile = Path("testResources/cliName.json")
        if (!expectFile.isRegularFile()) error("'cliName.json' does not exist")

        val expectContent = expectFile.readText().sanitizedLineEndings()

        val cliNames = supportedPlatforms.sortedBy { it.name }.associateWith { it.cliName }

        val actualContent = bazelManifestJson.encodeToString(cliNames).sanitizedLineEndings()

        if (expectContent != actualContent) {
            throw FileComparisonFailure(expectFile, expectContent, actualContent)
        }
    }

    @Test
    fun `cliName is unique`() {
        supportedPlatforms
            .groupBy { it.cliName }
            .forEach { (cliName, platforms) ->
                if (platforms.size > 1) {
                    error(
                        "Multiple platforms have the same cliName: '$cliName'; " +
                            "Platforms: ${platforms.joinToString { it.name }}",
                    )
                }
            }
    }
}
