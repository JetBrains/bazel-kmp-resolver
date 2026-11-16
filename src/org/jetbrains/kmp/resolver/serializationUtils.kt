package org.jetbrains.kmp.resolver

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform

internal val bazelManifestJson by lazy {
    Json {
        ignoreUnknownKeys = true
        allowStructuredMapKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }
}

internal val npmManifestJson by lazy {
    Json {
        ignoreUnknownKeys = true
        allowStructuredMapKeys = true
    }
}

/**
 * Serializes a [ResolutionPlatform] as its lowercased name (`jvm`, `wasm_js`, `ios_arm64`), which
 * [resolutionPlatformOf] decodes.
 *
 * This must be bound per property with `@Serializable(with = ...)`: an enum resolves to its
 * built-in serializer on its own, so a module-wide `contextual` registration is never consulted for
 * a plain `platform: ResolutionPlatform` property, which would be serialized by its constant name
 * (`JVM`, `WASM_JS`) instead.
 */
internal object ResolutionPlatformSerializer : KSerializer<ResolutionPlatform> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ResolutionPlatform", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ResolutionPlatform) {
        encoder.encodeString(value.name.lowercase())
    }

    override fun deserialize(decoder: Decoder): ResolutionPlatform {
        val raw = decoder.decodeString()
        return resolutionPlatformOf(raw)
            ?: throw SerializationException("Unknown resolution platform: '$raw'")
    }
}
