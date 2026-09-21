package org.jetbrains.kmp.resolver

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import org.jetbrains.amper.dependency.resolution.MavenRepository
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.Collections
import kotlin.test.assertEquals

object TestResourceReader {
    fun readResource(path: String): InputStream {
        return this::class.java.getResourceAsStream("/$path") ?: error("Resource not found: $path")
    }
}

internal data class RecordedRequest(val path: String, val authorizations: List<String>)

/**
 * Local HTTP server recording the `Authorization` headers of the requests it receives, so that tests can assert on
 * what was actually sent over the wire rather than on what we think we configured.
 */
internal class RecordingHttpServer(private val statusCode: Int = 200) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val recorded = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    val requests: List<RecordedRequest> get() = recorded.toList()
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            recorded.add(
                RecordedRequest(
                    path = exchange.requestURI.path,
                    authorizations = exchange.requestHeaders["Authorization"].orEmpty().toList(),
                ),
            )
            exchange.sendResponseHeaders(statusCode, -1)
            exchange.close()
        }
        server.start()
    }

    override fun close() = server.stop(0)
}

/**
 * Builds a [MultiplatformResolver] wired to the hermetic [NodeDistribution] provisioned by the `node` build plugin,
 * so the tests never depend on a node/npm installation of the host.
 */
internal fun testMultiplatformResolver(
    cachePath: Path,
    repositories: List<String>,
    artifactResolver: ArtifactUrlResolver,
    substitutions: Substitutions = emptyMap(),
): MultiplatformResolver = MultiplatformResolver(
    cachePath = cachePath,
    repositories = repositories.map { MavenRepository(it) },
    artifactResolver = artifactResolver,
    substitutions = substitutions,
    npmResolver = NpmResolver(
        nodeExecutable = NodeDistribution.node,
        npmCliJs = NodeDistribution.npmCliJs,
        registryUrl = null,
        packageVersionOverrides = emptyMap(),
        workDir = cachePath,
    ),
)

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
