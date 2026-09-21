package org.jetbrains.kmp.resolver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NpmAuthConfigTest {
    @Test
    fun `nerf dart strips the scheme and always ends with a slash`() {
        assertEquals("//registry.npmjs.org/", "https://registry.npmjs.org".toNpmNerfDart())
        assertEquals("//registry.npmjs.org/", "https://registry.npmjs.org/".toNpmNerfDart())
        assertEquals("//registry.example.com/api/npm/", "https://registry.example.com/api/npm/".toNpmNerfDart())
    }

    @Test
    fun `no credentials render an empty config`() {
        assertEquals("", npmAuthConfig("https://registry.npmjs.org", null))
        assertEquals(
            "",
            npmAuthConfig("https://registry.npmjs.org", RepositoryCredentials("https://registry.npmjs.org")),
        )
    }

    @Test
    fun `a bearer token becomes an auth token`() {
        val credentials = RepositoryCredentials(
            repositoryUrl = "https://registry.example.com/api/npm",
            headers = mapOf("Authorization" to listOf("Bearer helper-issued-token")),
        )

        assertEquals(
            "//registry.example.com/api/npm/:_authToken=helper-issued-token\n",
            npmAuthConfig("https://registry.example.com/api/npm", credentials),
        )
    }

    @Test
    fun `basic auth becomes the base64 auth entry`() {
        val credentials = RepositoryCredentials(
            repositoryUrl = "https://registry.example.com",
            username = "alice",
            password = "token-a",
        )

        assertEquals(
            "//registry.example.com/:_auth=YWxpY2U6dG9rZW4tYQ==\n",
            npmAuthConfig("https://registry.example.com", credentials),
        )
    }

    @Test
    fun `credentials npm cannot express fail loudly rather than being dropped`() {
        val unsupportedScheme = RepositoryCredentials(
            repositoryUrl = "https://registry.example.com",
            headers = mapOf("Authorization" to listOf("Negotiate abcdef")),
        )
        assertFailsWith<UnsupportedCredentialsException> {
            npmAuthConfig("https://registry.example.com", unsupportedScheme)
        }

        val extraHeader = RepositoryCredentials(
            repositoryUrl = "https://registry.example.com",
            headers = mapOf(
                "Authorization" to listOf("Bearer helper-issued-token"),
                "X-Jfrog-Art-Api" to listOf("some-key"),
            ),
        )
        assertFailsWith<UnsupportedCredentialsException> {
            npmAuthConfig("https://registry.example.com", extraHeader)
        }
    }
}
