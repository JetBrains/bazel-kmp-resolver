package org.jetbrains.kmp.resolver

import kotlin.test.Test
import kotlin.test.assertEquals

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
}