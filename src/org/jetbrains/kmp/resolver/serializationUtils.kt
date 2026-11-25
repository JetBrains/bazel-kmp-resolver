package org.jetbrains.kmp.resolver

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.amper.dependency.resolution.PlatformType
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

internal object MultiplatformLibraryIdSerializer : KSerializer<MultiplatformLibraryId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("MultiplatformLibraryId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: MultiplatformLibraryId) =
        encoder.encodeString(value.gav)

    override fun deserialize(decoder: Decoder): MultiplatformLibraryId =
        MultiplatformLibraryId.fromString(decoder.decodeString())
}

/**
 * Serializes and deserializes a [MultiplatformVariant] with its [MultiplatformVariant.platform] as
 * the only discriminator: no class discriminator is written, and the variant the platform of a JSON
 * object identifies is decoded, `jvm` a [MultiplatformVariant.Jvm], `wasm_js` a
 * [MultiplatformVariant.WasmJs], a native platform (e.g. `ios_arm64`) a
 * [MultiplatformVariant.Native], `android` a [MultiplatformVariant.Android].
 */
@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
internal object MultiplatformVariantSerializer : KSerializer<MultiplatformVariant> {
    override val descriptor: SerialDescriptor =
        buildSerialDescriptor("MultiplatformVariant", PolymorphicKind.SEALED)

    override fun serialize(encoder: Encoder, value: MultiplatformVariant): Unit =
        when (value) {
            is MultiplatformVariant.Jvm ->
                MultiplatformVariant.Jvm.serializer().serialize(encoder, value)

            is MultiplatformVariant.WasmJs ->
                MultiplatformVariant.WasmJs.serializer().serialize(encoder, value)

            is MultiplatformVariant.Native ->
                MultiplatformVariant.Native.serializer().serialize(encoder, value)

            is MultiplatformVariant.Android ->
                MultiplatformVariant.Android.serializer().serialize(encoder, value)
        }

    override fun deserialize(decoder: Decoder): MultiplatformVariant {
        if (decoder !is JsonDecoder)
            throw SerializationException(
                "This serializer can be used only with JsonDecoder, got $decoder",
            )

        /*
        Select the correct deserializer based on the 'platform' property.
         */
        val element = decoder.decodeJsonElement()

        val rawPlatformString =
            element.jsonObject["platform"]
                ?: throw SerializationException(
                    "Variant is not discriminated by any 'platform': $element",
                )

        val platform =
            resolutionPlatformOf(rawPlatformString.jsonPrimitive.content)
                ?: throw SerializationException("Unknown platform '$rawPlatformString'")

        val deserializer =
            when {
                platform == ResolutionPlatform.JVM -> MultiplatformVariant.Jvm.serializer()
                platform == ResolutionPlatform.WASM_JS -> MultiplatformVariant.WasmJs.serializer()
                platform.type == PlatformType.NATIVE -> MultiplatformVariant.Native.serializer()
                platform == ResolutionPlatform.ANDROID -> MultiplatformVariant.Android.serializer()
                else -> throw SerializationException("Unsupported platform '$platform'")
            }

        return decoder.json.decodeFromJsonElement(deserializer, element)
    }
}

internal class MultiplatformLibraryArtifactIntegritySerializer :
    KSerializer<MultiplatformLibraryArtifactIntegrity> {
    override val descriptor: SerialDescriptor
        get() =
            PrimitiveSerialDescriptor("MultiplatformLibraryArtifactIntegrity", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: MultiplatformLibraryArtifactIntegrity): Unit =
        encoder.encodeString(value.asBazelIntegrityString())

    override fun deserialize(decoder: Decoder): MultiplatformLibraryArtifactIntegrity {
        val bazelIntegrity = decoder.decodeString()
        return MultiplatformLibraryArtifactIntegrity.fromBazelIntegrityString(bazelIntegrity)
    }
}
