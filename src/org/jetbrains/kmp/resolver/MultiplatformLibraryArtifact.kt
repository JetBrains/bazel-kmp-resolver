package org.jetbrains.kmp.resolver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable(with = MultiplatformLibraryArtifactSerializer::class)
internal sealed class MultiplatformLibraryArtifact {
    abstract val integrity: MultiplatformLibraryArtifactIntegrity
    abstract val type: Type

    enum class Type {
        ClasspathJar,
        SourceJar,
        Klib,
        CInteropKlib,
        Aar,
        Npm,
    }
}

@Serializable
internal data class MavenMultiplatformLibraryArtifact(
    override val type: Type,
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
) : MultiplatformLibraryArtifact() {
    override val type = Type.Npm
}
