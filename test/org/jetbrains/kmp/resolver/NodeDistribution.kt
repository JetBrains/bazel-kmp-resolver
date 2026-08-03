package org.jetbrains.kmp.resolver

import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists

/**
 * Hermetic Node.js distribution used by the tests instead of resolving node/npm from the host. It is provisioned by
 * the `node` build plugin (see `build-sources/node`), which contributes the properties file read here as a test
 * resource, so the distribution is always in place before the tests run.
 */
internal object NodeDistribution {
    private const val RESOURCE = "/node-distribution.properties"

    val node: Path by lazy { path("node") }
    val npmCliJs: Path by lazy { path("npmCliJs") }

    private val properties: Properties by lazy {
        val stream = NodeDistribution::class.java.getResourceAsStream(RESOURCE)
            ?: error("$RESOURCE is missing, run `./kotlin do provisionNode` to provision the test Node.js distribution")
        Properties().also { properties -> stream.use(properties::load) }
    }

    private fun path(key: String): Path {
        val value = properties.getProperty(key) ?: error("Missing `$key` entry in $RESOURCE")
        return Path.of(value).also {
            check(it.exists()) { "$key does not exist at $it, run `./kotlin do provisionNode` to provision it again" }
        }
    }
}
