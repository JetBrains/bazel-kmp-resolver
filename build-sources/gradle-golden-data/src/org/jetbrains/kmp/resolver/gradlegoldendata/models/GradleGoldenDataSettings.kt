package org.jetbrains.kmp.resolver.gradlegoldendata.models

import java.nio.file.Path
import org.jetbrains.amper.plugins.Configurable

@Configurable
interface GradleGoldenDataSettings {
    /**
     * The directory of the checked-in Gradle test projects (see its README.md) whose
     * `resolveDependencies` task regenerates the golden data the resolver tests compare against.
     */
    val gradleProjectsDirectory: Path
}
