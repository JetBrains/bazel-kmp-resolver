package org.jetbrains.kmp.resolver.nativeimage.models

import java.nio.file.Path
import org.jetbrains.amper.plugins.Configurable

@Configurable
interface NativeImageSettings {
    val graalVmVersion: String
    val mainClass: String
    val outputDirectory: Path
    val archives: List<GraalVmArchive>
}
