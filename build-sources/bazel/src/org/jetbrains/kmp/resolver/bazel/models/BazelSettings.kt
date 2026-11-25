package org.jetbrains.kmp.resolver.bazel.models

import java.nio.file.Path
import org.jetbrains.amper.plugins.Configurable

@Configurable
interface BazelSettings {
    /** The rules_kotlin_external module directory, the Bazel module the commands build and test. */
    val rulesDirectory: Path

    /**
     * Where `./kotlin do buildNativeImage` writes the resolver binary: the `outputDirectory` of the
     * native-image plugin. Declared as a task input, it wires `buildNativeImage` as a dependency of
     * the bazel tasks, so they always run against the freshly built resolver.
     */
    val nativeBinaryDirectory: Path
}
