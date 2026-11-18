package org.jetbrains.kmp.resolver.nativeimage.models

import java.nio.file.Path
import org.jetbrains.amper.plugins.Configurable

/** Settings of the `testNativeImage` task, which runs the built native image end to end. */
@Configurable
interface NativeImageTestSettings {
    /**
     * The `node-distribution.properties` file the `provisionNode` task of the `node` plugin writes,
     * pointing at the hermetic Node.js distribution: resolving the `wasmjs` platform runs npm, so
     * the end-to-end test needs it.
     */
    val nodeDistributionPropertiesFile: Path

    /** The manifest the built native image is expected to produce, compared as a golden file. */
    val goldenManifestFile: Path
}
