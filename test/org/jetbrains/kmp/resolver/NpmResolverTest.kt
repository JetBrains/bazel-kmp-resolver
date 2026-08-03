package org.jetbrains.kmp.resolver

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NpmResolverTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun fixtureLock(): NpmPackageLock = TestResourceReader.readResource("npm-package-lock.json").use { input ->
        json.decodeFromStream(input)
    }

    private fun request(name: String): NpmResolutionRequest = NpmResolutionRequest(
        variantId = MultiplatformLibraryId("org.example", name, "1.0.0"),
        manifest = EmbeddedNpmManifest(name = name),
    )

    @Test
    fun `closure of a workspace member follows transitive dependencies and skips optional peers`() {
        val artifacts = fixtureLock().closureArtifacts(memberPath = "packages/lib-a", request = request("lib-a"))

        assertEquals(
            listOf(
                "@scope/x" to "1.2.3",
                "shared" to "2.0.0",
            ),
            artifacts.map { it.name to it.version },
        )
    }

    @Test
    fun `closure follows nested versions and workspace links without emitting workspace members`() {
        val artifacts = fixtureLock().closureArtifacts(memberPath = "packages/lib-b", request = request("lib-b"))

        assertEquals(
            setOf(
                "legacy" to "1.0.0",
                "shared" to "1.9.0",
                "@scope/x" to "1.2.3",
                "shared" to "2.0.0",
            ),
            artifacts.map { it.name to it.version }.toSet(),
        )
    }

    @Test
    fun `closure artifacts carry the registry url and integrity`() {
        val artifacts = fixtureLock().closureArtifacts(memberPath = "packages/lib-a", request = request("lib-a"))

        val scopedX = artifacts.single { it.name == "@scope/x" }
        assertEquals("https://registry.npmjs.org/@scope/x/-/x-1.2.3.tgz", scopedX.url)
        assertEquals("sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=", scopedX.integrity.asBazelIntegrityString())
    }

    @Test
    fun `closure of an unknown member fails`() {
        assertFailsWith<IllegalStateException> {
            fixtureLock().closureArtifacts(memberPath = "packages/unknown", request = request("unknown"))
        }
    }

    @Test
    fun `embedded npm manifest is read from the root of a klib`() {
        val klib = createTempDirectory("klib-fixture") / "library.klib"
        ZipOutputStream(klib.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("default/manifest"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("package.json"))
            zip.write(
                """{"name": "fixture-package", "version": "1.2.3", "main": "index.js", "dependencies": {"@js-joda/core": "3.2.0"}}"""
                    .toByteArray(),
            )
            zip.closeEntry()
        }

        assertEquals(
            EmbeddedNpmManifest(
                name = "fixture-package",
                version = "1.2.3",
                dependencies = mapOf("@js-joda/core" to "3.2.0"),
            ),
            readEmbeddedNpmManifest(klib),
        )
    }

    @Test
    fun `klib without embedded npm manifest yields null`() {
        val klib = createTempDirectory("klib-fixture") / "library.klib"
        ZipOutputStream(klib.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("default/manifest"))
            zip.closeEntry()
        }

        assertNull(readEmbeddedNpmManifest(klib))
    }
}
