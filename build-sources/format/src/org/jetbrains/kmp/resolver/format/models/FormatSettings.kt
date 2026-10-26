package org.jetbrains.kmp.resolver.format.models

import java.nio.file.Path
import org.jetbrains.amper.plugins.Configurable

@Configurable
interface FormatSettings {
    val ktfmt: KtfmtDistribution
    val directories: List<Path>
}

@Configurable
interface KtfmtDistribution {
    val version: String
    val archives: List<KtfmtArchive>
}

@Configurable
interface KtfmtArchive {
    val os: String
    val arch: String
    val url: String
    val sha256: String
    val executablePath: String
}
