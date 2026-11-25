package org.jetbrains.kmp.resolver

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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

@Serializable(with = MultiplatformLibraryArtifactSerializer::class)
internal sealed class MultiplatformLibraryArtifact {
    abstract val integrity: MultiplatformLibraryArtifactIntegrity
    abstract val type: Type

    enum class Type {
        ClasspathJar,
        SourceJar,
        Klib,
        CInteropKlib,
        Aar,
        Npm,
    }
}

@Serializable
internal data class MavenMultiplatformLibraryArtifact(
    override val type: Type,
    override val integrity: MultiplatformLibraryArtifactIntegrity,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val urls: List<String>,
) : MultiplatformLibraryArtifact()

@Serializable
@SerialName("npm")
internal data class NpmMultiplatformLibraryArtifact(
    override val integrity: MultiplatformLibraryArtifactIntegrity,
    val name: String,
    val version: String,
    val url: String,
) : MultiplatformLibraryArtifact() {
    override val type = Type.Npm
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
        val resolvedType =
            MultiplatformLibraryArtifact.Type.entries.find { it.name.equals(type.content, true) }
        if (resolvedType == null) throw SerializationException("Unknown type: ${type.content}")

        return when (resolvedType) {
            MultiplatformLibraryArtifact.Type.ClasspathJar,
            MultiplatformLibraryArtifact.Type.SourceJar,
            MultiplatformLibraryArtifact.Type.Klib,
            MultiplatformLibraryArtifact.Type.CInteropKlib,
            MultiplatformLibraryArtifact.Type.Aar -> {
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
