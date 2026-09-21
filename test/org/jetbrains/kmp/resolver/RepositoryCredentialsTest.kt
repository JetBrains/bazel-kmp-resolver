package org.jetbrains.kmp.resolver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepositoryCredentialsTest {
    @Test
    fun parsesCredentialsFromStream() {
        TestResourceReader.readResource("credentials.json").use { credentials ->
            val actual = RepositoryCredentials.fromStream(credentials)
            val expected = listOf(
                RepositoryCredentials(repositoryUrl = "repo.example.com", username = "alice", password = "token-a"),
                RepositoryCredentials(repositoryUrl = "packages.example.org", username = "bob", password = "token-b"),
            ).associateBy { it.repositoryUrl }
            assertEquals(expected, actual)
        }
    }

    @Test
    fun `parses headers alongside the legacy username and password`() {
        TestResourceReader.readResource("credentials-with-headers.json").use { credentials ->
            val actual = RepositoryCredentials.fromStream(credentials)
            val expected = listOf(
                RepositoryCredentials(
                    repositoryUrl = "https://repo.example.com/maven2",
                    headers = mapOf("Authorization" to listOf("Bearer helper-issued-token")),
                ),
                RepositoryCredentials(
                    repositoryUrl = "https://packages.example.org",
                    username = "bob",
                    password = "token-b",
                ),
            ).associateBy { it.repositoryUrl }
            assertEquals(expected, actual)
        }
    }

    @Test
    fun `username and password are sent as basic authorization`() {
        val credentials = RepositoryCredentials(repositoryUrl = "https://repo.example.com", username = "alice", password = "token-a")

        assertEquals(
            mapOf("Authorization" to listOf("Basic YWxpY2U6dG9rZW4tYQ==")),
            credentials.requestHeaders(),
        )
        assertEquals(BasicAuth("alice", "token-a"), credentials.basicAuth())
    }

    @Test
    fun `headers take precedence over username and password`() {
        val credentials = RepositoryCredentials(
            repositoryUrl = "https://repo.example.com",
            username = "alice",
            password = "token-a",
            headers = mapOf("Authorization" to listOf("Bearer helper-issued-token")),
        )

        assertEquals(mapOf("Authorization" to listOf("Bearer helper-issued-token")), credentials.requestHeaders())
    }

    @Test
    fun `a basic authorization header is decoded back so that amper can send it natively`() {
        val credentials = RepositoryCredentials(
            repositoryUrl = "https://repo.example.com",
            headers = mapOf("authorization" to listOf("Basic YWxpY2U6dG9rZW4tYQ==")),
        )

        assertEquals(BasicAuth("alice", "token-a"), credentials.basicAuth())
        assertEquals(
            listOf(MavenRepositoryCredentials("https://repo.example.com", "alice", "token-a")),
            listOf("https://repo.example.com")
                .withRepositoryCredentials(mapOf("https://repo.example.com" to credentials))
                .map { MavenRepositoryCredentials(it.url, it.userName, it.password) },
        )
    }

    @Test
    fun `a bearer token cannot be expressed as basic auth and requires header injection`() {
        val credentials = RepositoryCredentials(
            repositoryUrl = "https://repo.example.com",
            headers = mapOf("Authorization" to listOf("Bearer helper-issued-token")),
        )

        assertNull(credentials.basicAuth())
        assertEquals(true, RepositoryCredentialsResolver(listOf(credentials)).requiresHeaderInjection)
    }

    @Test
    fun `basic-only credentials are left to amper and need no header injection`() {
        val fromNetrc = RepositoryCredentials(repositoryUrl = "https://repo.example.com", username = "alice", password = "token-a")
        val fromHelper = RepositoryCredentials(
            repositoryUrl = "https://packages.example.org",
            headers = mapOf("Authorization" to listOf("Basic YWxpY2U6dG9rZW4tYQ==")),
        )

        assertEquals(false, RepositoryCredentialsResolver(listOf(fromNetrc, fromHelper)).requiresHeaderInjection)
    }

    @Test
    fun `repositories without credentials resolve to none`() {
        val resolver = RepositoryCredentialsResolver(
            listOf(RepositoryCredentials(repositoryUrl = "https://repo.example.com/maven2")),
        )

        assertEquals(emptyMap(), resolver.headersFor("https://repo.example.com/maven2/org/example/lib.pom"))
        assertEquals(false, resolver.requiresHeaderInjection)
    }
}

private data class MavenRepositoryCredentials(val url: String, val username: String?, val password: String?)
