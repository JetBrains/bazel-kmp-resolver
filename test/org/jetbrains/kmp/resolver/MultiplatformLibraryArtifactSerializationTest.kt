package org.jetbrains.kmp.resolver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class MultiplatformLibraryArtifactSerializationTest {
    @Test
    fun `manifest artifact types are decoded case insensitively`() {
        val mavenArtifact =
            bazelManifestJson.decodeFromString<MultiplatformLibraryArtifact>(
                """{"type":"classpathjar","integrity":"sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","groupId":"example","artifactId":"library","version":"1.0","urls":[]}""",
            )
        val npmArtifact =
            bazelManifestJson.decodeFromString<MultiplatformLibraryArtifact>(
                """{"type":"nPm","integrity":"sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","name":"example","version":"1.0","url":"https://example.test/library.tgz"}""",
            )

        assertIs<MavenMultiplatformLibraryArtifact>(mavenArtifact)
        assertEquals(MultiplatformLibraryArtifact.Type.Maven.ClasspathJar, mavenArtifact.type)
        assertIs<NpmMultiplatformLibraryArtifact>(npmArtifact)
        assertEquals(MultiplatformLibraryArtifact.Type.Npm, npmArtifact.type)

        val mavenJson =
            bazelManifestJson.encodeToJsonElement<MultiplatformLibraryArtifact>(mavenArtifact)
        val npmJson =
            bazelManifestJson.encodeToJsonElement<MultiplatformLibraryArtifact>(npmArtifact)
        assertEquals(JsonPrimitive("ClasspathJar"), mavenJson.jsonObject["type"])
        assertEquals(JsonPrimitive("Npm"), npmJson.jsonObject["type"])
    }

    @Test
    fun `unknown manifest artifact type throws serialization exception`() {
        assertFailsWith<SerializationException> {
            bazelManifestJson.decodeFromString<MultiplatformLibraryArtifact>(
                """{"type":"unknown","integrity":"sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","name":"example","version":"1.0","url":"https://example.test/library.tgz"}""",
            )
        }
    }
}
