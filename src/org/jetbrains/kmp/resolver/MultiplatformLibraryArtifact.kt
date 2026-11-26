package org.jetbrains.kmp.resolver

import kotlinx.serialization.Serializable

@Serializable(with = MultiplatformLibraryArtifactSerializer::class)
internal sealed class MultiplatformLibraryArtifact {
    abstract val integrity: MultiplatformLibraryArtifactIntegrity
    abstract val type: Type<*>

    sealed interface Type<T : MultiplatformLibraryArtifact> {

        val name
            get() =
                when (this) {
                    Maven.Aar -> "Aar"
                    Maven.CInteropKlib -> "CInteropKlib"
                    Maven.ClasspathJar -> "ClasspathJar"
                    Maven.Klib -> "Klib"
                    Maven.SourceJar -> "SourceJar"
                    Npm -> "Npm"
                }

        sealed interface Maven : Type<MavenMultiplatformLibraryArtifact> {
            data object ClasspathJar : Maven

            data object SourceJar : Maven

            data object Klib : Maven

            data object CInteropKlib : Maven

            data object Aar : Maven
        }

        data object Npm : Type<NpmMultiplatformLibraryArtifact>

        companion object {
            val values by lazy {
                listOf(
                    Maven.Aar,
                    Maven.CInteropKlib,
                    Maven.ClasspathJar,
                    Maven.Klib,
                    Maven.SourceJar,
                    Npm,
                )
            }
        }
    }
}

internal data class MavenMultiplatformLibraryArtifact(
    override val type: Type.Maven,
    override val integrity: MultiplatformLibraryArtifactIntegrity,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val urls: List<String>,
) : MultiplatformLibraryArtifact()

internal data class NpmMultiplatformLibraryArtifact(
    override val integrity: MultiplatformLibraryArtifactIntegrity,
    val name: String,
    val version: String,
    val url: String,
) : MultiplatformLibraryArtifact() {
    override val type = Type.Npm
}
