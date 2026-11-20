package org.jetbrains.kmp.resolver.node.tasks

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class ProvisionNodeTest {
    @Test
    fun `properties preserve windows and unix paths`() {
        val windowsNode = "C:\\Users\\Rénée Example\\node\\node.exe"
        val windowsNpmCliJs = "C:\\Users\\Rénée Example\\node\\node_modules\\npm\\bin\\npm-cli.js"
        val unixNode = "/Users/Rénée Example/node/bin/node"
        val unixNpmCliJs = "/Users/Rénée Example/node/lib/node_modules/npm/bin/npm-cli.js"
        val output = ByteArrayOutputStream()

        writeNodeDistributionProperties(
            output = output,
            node = windowsNode,
            npmCliJs = windowsNpmCliJs,
        )

        val properties = Properties()
        ByteArrayInputStream(output.toByteArray()).use(properties::load)

        assertEquals(windowsNode, properties.getProperty("node"))
        assertEquals(windowsNpmCliJs, properties.getProperty("npmCliJs"))

        output.reset()
        writeNodeDistributionProperties(
            output = output,
            node = unixNode,
            npmCliJs = unixNpmCliJs,
        )
        ByteArrayInputStream(output.toByteArray()).use(properties::load)

        assertEquals(unixNode, properties.getProperty("node"))
        assertEquals(unixNpmCliJs, properties.getProperty("npmCliJs"))
    }
}
