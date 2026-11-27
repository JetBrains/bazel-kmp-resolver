package org.jetbrains.kmp.resolver

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

internal object MultiplatformLibraryArtifactSerializer : KSerializer<MultiplatformLibraryArtifact> {
    override val descriptor = buildClassSerialDescriptor("MultiplatformLibraryArtifact")

    override fun deserialize(decoder: Decoder): MultiplatformLibraryArtifact {
        decoder as? JsonDecoder
            ?: throw SerializationException("Expected JsonDecoder, got ${decoder::class}")
        val element = decoder.decodeJsonElement().jsonObject
        val type = element["type"]?.jsonPrimitive ?: throw SerializationException("Missing 'type'")
        if (!type.isString)
            throw SerializationException("'type' must be a string, got: ${type.content}")
        val resolvedType = artifactTypeOf(type.content)

        return when (resolvedType) {
            is MultiplatformLibraryArtifact.Type.Maven -> {
                MavenMultiplatformLibraryArtifact(
                    type = resolvedType,
                    integrity =
                        decoder.json.decodeFromJsonElement(
                            element["integrity"]
                                ?: throw SerializationException(
                                    "Missing 'integrity'",
                                ),
                        ),
                    groupId =
                        element["groupId"]?.jsonPrimitive?.contentOrNull
                            ?: throw SerializationException("Missing 'groupId'"),
                    artifactId =
                        element["artifactId"]?.jsonPrimitive?.contentOrNull
                            ?: throw SerializationException("Missing 'artifactId'"),
                    version =
                        element["version"]?.jsonPrimitive?.contentOrNull
                            ?: throw SerializationException("Missing 'version'"),
                    urls =
                        element["urls"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                            ?: throw SerializationException("Missing 'urls'"),
                )
            }

            MultiplatformLibraryArtifact.Type.Npm -> {
                NpmMultiplatformLibraryArtifact(
                    integrity =
                        decoder.json.decodeFromJsonElement(
                            element["integrity"]
                                ?: throw SerializationException("Missing 'integrity'"),
                        ),
                    name =
                        element["name"]?.jsonPrimitive?.contentOrNull
                            ?: throw SerializationException("Missing 'name'"),
                    version =
                        element["version"]?.jsonPrimitive?.contentOrNull
                            ?: throw SerializationException("Missing 'version'"),
                    url =
                        element["url"]?.jsonPrimitive?.contentOrNull
                            ?: throw SerializationException("Missing 'url'"),
                )
            }
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: MultiplatformLibraryArtifact,
    ) {
        encoder as? JsonEncoder
            ?: throw SerializationException("Expected JsonEncoder, got ${encoder::class}")
        val jsonObj = buildJsonObject {
            put("type", value.type.name)
            when (value) {
                is MavenMultiplatformLibraryArtifact -> {
                    put("integrity", encoder.json.encodeToJsonElement(value.integrity))
                    put("groupId", value.groupId)
                    put("artifactId", value.artifactId)
                    put("version", value.version)
                    put("urls", JsonArray(value.urls.map(::JsonPrimitive)))
                }

                is NpmMultiplatformLibraryArtifact -> {
                    put("name", value.name)
                    put("version", value.version)
                    put("integrity", encoder.json.encodeToJsonElement(value.integrity))
                    put("url", value.url)
                }
            }
        }

        encoder.encodeJsonElement(jsonObj)
    }
}

private fun artifactTypeOf(raw: String): MultiplatformLibraryArtifact.Type<*> =
    MultiplatformLibraryArtifact.Type.values.firstOrNull { type ->
        type.name.equals(raw, ignoreCase = true)
    } ?: throw SerializationException("Unknown artifact type: $raw")

internal object MultiplatformLibraryIdSerializer : KSerializer<MultiplatformLibraryId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("MultiplatformLibraryId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: MultiplatformLibraryId) =
        encoder.encodeString(value.gav)

    override fun deserialize(decoder: Decoder): MultiplatformLibraryId =
        MultiplatformLibraryId.fromString(decoder.decodeString())
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
