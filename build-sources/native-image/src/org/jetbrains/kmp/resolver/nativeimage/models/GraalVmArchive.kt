package org.jetbrains.kmp.resolver.nativeimage.models

import org.jetbrains.amper.plugins.Configurable

@Configurable
interface GraalVmArchive {
    val os: String
    val arch: String
    val url: String
    val sha256: String
    val nativeImagePath: String
}