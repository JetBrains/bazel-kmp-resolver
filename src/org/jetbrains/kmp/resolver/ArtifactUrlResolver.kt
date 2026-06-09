package org.jetbrains.kmp.resolver

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.amper.dependency.resolution.MavenRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

internal data class UnresolvedMultiplatformLibraryArtifact(
    val sha256checksum: String?,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val artifactPath: String,
)

internal data class UnresolvedNode(
    val id: MultiplatformLibraryId,
    val variantId: MultiplatformLibraryId,
    val klib: UnresolvedMultiplatformLibraryArtifact,
    val sourceJar: UnresolvedMultiplatformLibraryArtifact?,
    val dependencies: List<MultiplatformLibraryId>,
    val exportedDependencies: List<MultiplatformLibraryId>,
)

internal suspend fun UnresolvedMultiplatformLibraryArtifact.resolve(
    repositories: List<MavenRepository>,
    artifactUrlResolver: ArtifactUrlResolver,
): MultiplatformLibraryArtifact = coroutineScope {
    MultiplatformLibraryArtifact(
        sha256checksum = sha256checksum,
        groupId = groupId,
        artifactId = artifactId,
        version = version,
        urls = repositories.map {
            async {
                artifactUrlResolver.artifactExistsAt(it, artifactPath)
            }
        }.awaitAll().filterIsInstance<ArtifactFile.Resolved>().map { it.url },
    )
}

internal suspend fun UnresolvedNode.resolve(
    repositories: List<MavenRepository>,
    artifactUrlResolver: ArtifactUrlResolver,
): MultiplatformLibrary = coroutineScope {
    val resolvedKlib = async { klib.resolve(repositories, artifactUrlResolver) }
    val resolvedSourceJar = sourceJar?.let { async { it.resolve(repositories, artifactUrlResolver) } }

    MultiplatformLibrary(
        id = id,
        variantId = variantId,
        klib = resolvedKlib.await(),
        sourceJar = resolvedSourceJar?.await(),
        dependencies = dependencies,
        exportedDependencies = exportedDependencies,
    )
}

internal sealed class ArtifactFile {
    data class Resolved(val url: String) : ArtifactFile()
    object NotFound : ArtifactFile()
}

internal class ArtifactUrlResolver : AutoCloseable {
    val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val httpClient = HttpClient(CIO) {
        followRedirects = true
        expectSuccess = false
    }
    private val availabilityByUrl = ConcurrentHashMap<String, ArtifactFile>()

    suspend fun artifactExistsAt(repository: MavenRepository, artifactPath: String): ArtifactFile {
        val artifactUrl = "${repository.url.trimEnd('/')}/$artifactPath"
        return availabilityByUrl.getOrPut(artifactUrl) {
            logger.info("[$artifactUrl] checking for existence of artifact...")
            val resolved = httpClient.head {
                url(artifactUrl)
                val username = repository.userName
                val password = repository.password
                when {
                    username != null && password != null -> basicAuth(username, password)
                    else -> {}
                }
            }.status.isSuccess()
            when {
                resolved -> {
                    logger.info("[$artifactUrl] found")
                    ArtifactFile.Resolved(artifactUrl)
                }
                else -> {
                    logger.info("[$artifactUrl] not found")
                    ArtifactFile.NotFound
                }
            }
        }
    }

    override fun close() {
        httpClient.close()
    }
}
