package org.jetbrains.kmp.resolver.auth

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.jetbrains.kmp.resolver.TestResourceReader

class RepositoryCredentialsTest {
    @Test
    fun parsesCredentialsFromStream() {
        TestResourceReader.readResource("credentials.json").use { credentials ->
            val actual = RepositoryCredentials.fromStream(credentials)
            val expected =
                listOf(
                    RepositoryCredentials(
                        repositoryUrl = "repo.example.com",
                        username = "alice",
                        password = "token-a",
                    ),
                    RepositoryCredentials(
                        repositoryUrl = "packages.example.org",
                        username = "bob",
                        password = "token-b",
                    ),
                )
            assertEquals(expected, actual)
        }
    }

    @Test
    fun `basic credentials apply under the longest matching repository URL`() = runBlocking {
        val credentials =
            BasicRepositoryCredentials(
                listOf(
                    RepositoryCredentials(
                        repositoryUrl = "https://repo.example.com/maven",
                        username = "alice",
                        password = "a",
                    ),
                    RepositoryCredentials(
                        repositoryUrl = "https://repo.example.com/maven/private/",
                        username = "bob",
                        password = "b",
                    ),
                    RepositoryCredentials(
                        repositoryUrl = "https://anonymous.example.com/maven",
                        username = null,
                        password = null,
                    ),
                ),
            )

        assertEquals(
            mapOf("Authorization" to listOf("Basic YWxpY2U6YQ==")),
            credentials.headersFor(URI("https://repo.example.com/maven/com/a/a.jar")),
        )
        assertEquals(
            mapOf("Authorization" to listOf("Basic Ym9iOmI=")),
            credentials.headersFor(URI("https://repo.example.com/maven/private/a.jar")),
        )
        assertNull(credentials.headersFor(URI("https://repo.example.com/mavenX/a.jar")))
        assertNull(credentials.headersFor(URI("https://anonymous.example.com/maven/a.jar")))
        assertNull(credentials.headersFor(URI("https://repo1.maven.org/maven2/a.jar")))
    }
}
