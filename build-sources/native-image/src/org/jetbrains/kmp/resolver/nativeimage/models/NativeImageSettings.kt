package org.jetbrains.kmp.resolver.nativeimage.models

import org.jetbrains.amper.plugins.Configurable
import java.nio.file.Path

@Configurable
interface NativeImageSettings {
    val graalVmVersion: String
    val mainClass: String
    val outputDirectory: Path
    val archives: List<GraalVmArchive>
}
