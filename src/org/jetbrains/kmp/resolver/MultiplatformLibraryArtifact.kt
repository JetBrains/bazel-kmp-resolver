package org.jetbrains.kmp.resolver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed class MultiplatformLibraryArtifact {
    abstract val integrity: MultiplatformLibraryArtifactIntegrity
}

@Serializable
@SerialName("maven")
internal data class MavenMultiplatformLibraryArtifact(
    override val integrity: MultiplatformLibraryArtifactIntegrity,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val urls: List<String>,
) : MultiplatformLibraryArtifact()

@Serializable
@SerialName("npm")
internal data class NpmMultiplatformLibraryArtifact(
    override val integrity: MultiplatformLibraryArtifactIntegrity,
    val name: String,
    val version: String,
    val url: String,
) : MultiplatformLibraryArtifact()
