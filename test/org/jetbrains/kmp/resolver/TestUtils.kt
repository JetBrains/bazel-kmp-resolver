package org.jetbrains.kmp.resolver

import kotlinx.serialization.json.Json
import java.io.InputStream
import kotlin.test.assertEquals

object TestResourceReader {
    fun readResource(path: String): InputStream {
        return this::class.java.getResourceAsStream("/$path") ?: error("Resource not found: $path")
    }
}

private val json = Json {
    prettyPrintIndent = "  "
    prettyPrint = true
}

/**
 * Asserts using the given [manifestResourceFilepath] to improve readability of diff in tests, it's easier to read
 * a JSON diff.
 */
internal fun assertUsingManifest(
    coordinates: List<String>,
    repositories: List<String>,
    libraries: List<MultiplatformVariant>,
    manifestResourceFilepath: String,
) {
    val actual = BazelManifest(
        askedCoordinates = coordinates.sorted(),
        askedRepositories = repositories.sorted(),
        libraries = libraries.asLibraries(),
    )
    val actualJson = json.encodeToString(BazelManifest.serializer(), actual)

    TestResourceReader.readResource(manifestResourceFilepath).use { expected ->
        val expectedManifest = expected.readAllBytes()
        assertEquals(expectedManifest.toString(Charsets.UTF_8), actualJson)
    }
}
