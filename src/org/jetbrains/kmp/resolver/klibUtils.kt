package org.jetbrains.kmp.resolver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class KlibRole {
    /** The klib of the library itself, of which a variant has exactly one. */
    @SerialName("main") MAIN,

    /** A klib binding a C library the library uses, produced by cinterop. */
    @SerialName("cinterop") CINTEROP,
}

/**
 * Matches the klibs binding C libraries, which Kotlin publishes next to the main klib of a native
 * variant with a `cinterop-<name>` classifier
 * (`ktor-client-curl-linuxx64-3.4.3-cinterop-libcurl.klib` in the repository), named
 * `<artifact>-<Target>Cinterop-<name>-<version>.klib` in Gradle module metadata
 * (`ktor-client-curl-linuxX64Cinterop-libcurlMain-3.4.3.klib`).
 */
private val CINTEROP_KLIB = Regex("cinterop-", RegexOption.IGNORE_CASE)

/**
 * Role of the klib named [fileName] in the variant [artifactId]; the artifact ID is left out of the
 * match, so that a library whose name contains `cinterop` keeps its main klib.
 */
internal fun klibRole(artifactId: String, fileName: String): KlibRole =
    when {
        CINTEROP_KLIB.containsMatchIn(fileName.removePrefix(artifactId)) -> KlibRole.CINTEROP
        else -> KlibRole.MAIN
    }
