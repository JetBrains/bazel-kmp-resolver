package org.jetbrains.kmp.resolver

import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable

@Serializable(with = MultiplatformLibraryArtifactIntegritySerializer::class)
internal sealed class MultiplatformLibraryArtifactIntegrity {
    abstract val hash: String
    abstract val algorithm: String

    data class Sha256(override val hash: String) : MultiplatformLibraryArtifactIntegrity() {
        override val algorithm: String = "sha256"
    }

    /**
     * Fallback for repositories publishing neither a sha512 nor a sha256 checksum: Maven Central
     * only publishes sha1 and md5 checksums for artifacts deployed without Gradle module metadata,
     * i.e. most JVM-only libraries.
     */
    data class Sha1(override val hash: String) : MultiplatformLibraryArtifactIntegrity() {
        override val algorithm: String = "sha1"
    }

    data class Sha512(override val hash: String) : MultiplatformLibraryArtifactIntegrity() {
        override val algorithm: String = "sha512"
    }

    fun asBazelIntegrityString(): String {
        val base64Hash = Base64.encode(hash.hexToBytes())
        return "$algorithm-$base64Hash"
    }

    companion object {
        fun fromBazelIntegrityString(
            bazelIntegrity: String,
        ): MultiplatformLibraryArtifactIntegrity {
            val (algorithm, base64Hash) = bazelIntegrity.split("-", limit = 2)
            val hash = Base64.decode(base64Hash).toHexString()
            return fromHash(algorithm, hash)
        }

        fun fromHash(algorithm: String, hash: String): MultiplatformLibraryArtifactIntegrity =
            when (algorithm) {
                "sha1" -> Sha1(hash)
                "sha256" -> Sha256(hash)
                "sha512" -> Sha512(hash)
                else -> throw IllegalArgumentException("Unknown hash algorithm: $algorithm")
            }
    }
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hash must have an even number of hexadecimal characters: $this" }
    return ByteArray(length / 2) { index ->
        val high = this[index * 2].hexDigitToInt()
        val low = this[index * 2 + 1].hexDigitToInt()
        ((high shl 4) or low).toByte()
    }
}

private fun ByteArray.toHexString(): String =
    buildString(size * 2) {
        for (byte in this@toHexString) {
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }

private fun Char.hexDigitToInt(): Int =
    when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> throw IllegalArgumentException("Invalid hexadecimal digit: $this")
    }
