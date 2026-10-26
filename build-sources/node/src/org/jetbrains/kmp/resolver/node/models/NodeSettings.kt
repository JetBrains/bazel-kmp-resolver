package org.jetbrains.kmp.resolver.node.models

import java.nio.file.Path
import org.jetbrains.amper.plugins.Configurable

@Configurable
interface NodeSettings {
    val version: String
    val distributionDirectory: Path
    val generatedResourcesDirectory: Path
    val distributions: List<NodeDistributionArchive>
}

@Configurable
interface NodeDistributionArchive {
    val os: String
    val arch: String
    val url: String
    val sha256: String
    val nodePath: String
    val npmCliJsPath: String
}
