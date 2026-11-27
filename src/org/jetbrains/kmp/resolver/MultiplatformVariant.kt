package org.jetbrains.kmp.resolver

import kotlinx.serialization.Serializable
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform

@Serializable
internal data class MultiplatformVariant(
    @Serializable(with = ResolutionPlatformSerializer::class) val platform: ResolutionPlatform,
    /**
     * ID of the parent KMP umbrella library from which this variant is from.
     *
     * For a library that is not a variant of a KMP library (a JVM-only library, or a KMP library
     * publishing its JVM artifact in the umbrella module itself like `kotlin-stdlib`), this is the
     * same as [variantId].
     */
    val id: MultiplatformLibraryId,

    /**
     * ID of the resolved multiplatform library, usually [id] would point to the umbrella library
     * like `kotlin-stdlib`, while [variantId] would point to the variant library like
     * `kotlin-stdlib-wasm-js`.
     */
    val variantId: MultiplatformLibraryId,

    /** Dependencies of this library, exposed to the link path of dependents transitively. */
    val dependencies: List<SubstitutionId>,
    /**
     * Dependencies of this library, exposed to the link path of dependents transitively, and
     * exposed to the compile library path of *direct* dependents.
     */
    val exportedDependencies: List<SubstitutionId>,
    val artifacts: List<MultiplatformLibraryArtifact>,
) {

    fun <T : MultiplatformLibraryArtifact> getArtifacts(
        ofType: MultiplatformLibraryArtifact.Type<T>,
    ): List<T> {
        return artifacts
            .filter { it.type == ofType }
            .map {
                /* Enforced by the MultiplatformLibraryArtifact contract */
                @Suppress("UNCHECKED_CAST")
                it as T
            }
    }
}
