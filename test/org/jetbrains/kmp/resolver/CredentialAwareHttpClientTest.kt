package org.jetbrains.kmp.resolver

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class CredentialAwareHttpClientTest {
    private fun bearerCredentials(repositoryUrl: String) = RepositoryCredentials(
        repositoryUrl = repositoryUrl,
        headers = mapOf("Authorization" to listOf("Bearer resolved-token")),
    )

    private fun get(client: HttpClient, url: String) {
        client.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
    }

    @Test
    fun `attaches the resolved headers to requests of the repository`() {
        RecordingHttpServer().use { server ->
            val credentials = RepositoryCredentialsResolver(listOf(bearerCredentials("${server.baseUrl}/maven2")))
            val client = CredentialAwareHttpClient(HttpClient.newHttpClient(), credentials)

            get(client, "${server.baseUrl}/maven2/org/example/lib/1.0/lib-1.0.pom")

            assertEquals(listOf(listOf("Bearer resolved-token")), server.requests.map { it.authorizations })
        }
    }

    @Test
    fun `replaces an authorization header already set by the caller instead of appending to it`() {
        RecordingHttpServer().use { server ->
            val credentials = RepositoryCredentialsResolver(listOf(bearerCredentials("${server.baseUrl}/maven2")))
            val client = CredentialAwareHttpClient(HttpClient.newHttpClient(), credentials)

            // this is what Amper adds by itself out of `MavenRepository.userName`/`password`
            client.send(
                HttpRequest.newBuilder(URI.create("${server.baseUrl}/maven2/org/example/lib/1.0/lib-1.0.pom"))
                    .header("Authorization", "Basic dXNlcjpwYXNzd29yZA==")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )

            assertEquals(listOf(listOf("Bearer resolved-token")), server.requests.map { it.authorizations })
        }
    }

    @Test
    fun `sends no credentials to a URL outside of the repositories they were resolved for`() {
        RecordingHttpServer().use { server ->
            val credentials = RepositoryCredentialsResolver(listOf(bearerCredentials("${server.baseUrl}/private")))
            val client = CredentialAwareHttpClient(HttpClient.newHttpClient(), credentials)

            get(client, "${server.baseUrl}/public/org/example/lib/1.0/lib-1.0.pom")
            // a repository URL is only a prefix match on path boundaries, `/private-mirror` is a different one
            get(client, "${server.baseUrl}/private-mirror/org/example/lib/1.0/lib-1.0.pom")

            assertEquals(listOf(emptyList(), emptyList()), server.requests.map { it.authorizations })
        }
    }

    @Test
    fun `the longest matching repository URL wins`() {
        RecordingHttpServer().use { server ->
            val credentials = RepositoryCredentialsResolver(
                listOf(
                    RepositoryCredentials(
                        repositoryUrl = server.baseUrl,
                        headers = mapOf("Authorization" to listOf("Bearer host-token")),
                    ),
                    RepositoryCredentials(
                        repositoryUrl = "${server.baseUrl}/maven2/releases",
                        headers = mapOf("Authorization" to listOf("Bearer releases-token")),
                    ),
                ),
            )
            val client = CredentialAwareHttpClient(HttpClient.newHttpClient(), credentials)

            get(client, "${server.baseUrl}/maven2/releases/org/example/lib/1.0/lib-1.0.pom")
            get(client, "${server.baseUrl}/maven2/snapshots/org/example/lib/1.0/lib-1.0.pom")

            assertEquals(
                listOf(listOf("Bearer releases-token"), listOf("Bearer host-token")),
                server.requests.map { it.authorizations },
            )
        }
    }
}
