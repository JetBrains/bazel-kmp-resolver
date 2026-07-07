package org.jetbrains.kmp.resolver.nativeimage

internal data class Platform(
    val os: String,
    val arch: String,
) {
    val suffix = "$os-$arch"
    val executableExtension = when (os) {
        "windows" -> ".exe"
        else -> ""
    }
    val classpathSeparator = when (os) {
        "windows" -> ";"
        else -> ":"
    }

    companion object {
        fun current(): Platform = Platform(
            os = System.getProperty("os.name").normalizedOs(),
            arch = System.getProperty("os.arch").normalizedArch(),
        )
    }
}

internal fun String.normalizedOs(): String = when {
    lowercase().contains("win") -> "windows"
    lowercase().contains("mac") || lowercase().contains("darwin") -> "macos"
    lowercase().contains("linux") -> "linux"
    else -> error("Unsupported operating system: $this")
}

internal fun String.normalizedArch(): String = when (lowercase()) {
    "x64", "x86_64", "amd64" -> "x64"
    "arm64", "aarch64" -> "arm64"
    else -> error("Unsupported CPU architecture: $this")
}