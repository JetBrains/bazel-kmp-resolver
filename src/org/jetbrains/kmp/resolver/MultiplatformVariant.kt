package org.jetbrains.kmp.resolver

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import org.jetbrains.amper.dependency.resolution.PlatformType
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform

@Serializable(with = MultiplatformVariantSerializer::class)
internal sealed class MultiplatformVariant {

    @Serializable(with = ResolutionPlatformSerializer::class)
    abstract val platform: ResolutionPlatform

    /**
     * ID of the parent KMP umbrella library from which this variant is from.
     *
     * For a library that is not a variant of a KMP library (a JVM-only library, or a KMP library
     * publishing its JVM artifact in the umbrella module itself like `kotlin-stdlib`), this is the
     * same as [variantId].
     */
    abstract val id: MultiplatformLibraryId

    /**
     * ID of the resolved multiplatform library, usually [id] would point to the umbrella library
     * like `kotlin-stdlib`, while [variantId] would point to the variant library like
     * `kotlin-stdlib-wasm-js`.
     */
    abstract val variantId: MultiplatformLibraryId

    /** Dependencies of this library, exposed to the link path of dependents transitively. */
    abstract val dependencies: List<SubstitutionId>

    /**
     * Dependencies of this library, exposed to the link path of dependents transitively, and
     * exposed to the compile library path of *direct* dependents.
     */
    abstract val exportedDependencies: List<SubstitutionId>

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class WasmJs(
        @Serializable(with = ResolutionPlatformSerializer::class)
        @EncodeDefault
        override val platform: ResolutionPlatform = ResolutionPlatform.WASM_JS,
        override val id: MultiplatformLibraryId,
        override val variantId: MultiplatformLibraryId,
        override val dependencies: List<SubstitutionId>,
        override val exportedDependencies: List<SubstitutionId>,

        /**
         * .klib of this imported dependency, exposed to the compile library path of direct
         * dependents.
         */
        val klib: MavenMultiplatformLibraryArtifact,
        val sourceJar: MavenMultiplatformLibraryArtifact?,

        /**
         * NPM packages required at runtime by this library, declared by the `package.json` embedded
         * in its klib, listed with their transitive NPM dependencies.
         */
        val npmPackages: List<NpmMultiplatformLibraryArtifact> = emptyList(),
    ) : MultiplatformVariant()

    @Serializable
    data class Native(
        @Serializable(with = ResolutionPlatformSerializer::class)
        override val platform: ResolutionPlatform,
        override val id: MultiplatformLibraryId,
        override val variantId: MultiplatformLibraryId,
        override val dependencies: List<SubstitutionId>,
        override val exportedDependencies: List<SubstitutionId>,
        val klib: MavenMultiplatformLibraryArtifact,
        val cinteropKlibs: List<MavenMultiplatformLibraryArtifact>,
        val sourceJar: MavenMultiplatformLibraryArtifact?,
    ) : MultiplatformVariant() {
        init {
            require(platform.type == PlatformType.NATIVE) {
                "Expected native target. Found '$platform'"
            }
        }
    }

    @Serializable
    data class Jvm(
        @Serializable(with = ResolutionPlatformSerializer::class)
        @EncodeDefault
        override val platform: ResolutionPlatform = ResolutionPlatform.JVM,
        override val id: MultiplatformLibraryId,
        override val variantId: MultiplatformLibraryId,
        override val dependencies: List<SubstitutionId>,
        override val exportedDependencies: List<SubstitutionId>,

        /**
         * .jar of this imported dependency, exposed to the compile and runtime class paths of
         * dependents.
         */
        val jar: MavenMultiplatformLibraryArtifact,
        val sourceJar: MavenMultiplatformLibraryArtifact?,
    ) : MultiplatformVariant()

    @Serializable
    data class Android(
        @Serializable(with = ResolutionPlatformSerializer::class)
        @EncodeDefault
        override val platform: ResolutionPlatform = ResolutionPlatform.ANDROID,
        override val id: MultiplatformLibraryId,
        override val variantId: MultiplatformLibraryId,
        override val dependencies: List<SubstitutionId>,
        override val exportedDependencies: List<SubstitutionId>,

        /**
         * .aar of this imported dependency, exposed to the compile and runtime class paths of
         * dependents: the artifact a consumer of the library uses when one is published, as it
         * carries what a .jar does not, like the library's manifest and resources.
         */
        val aar: MavenMultiplatformLibraryArtifact?,

        /**
         * .jar of this imported dependency, exposed to the compile and runtime class paths of
         * dependents when no .aar is published: a JAR-packaged Android library, like
         * `kotlinx-coroutines-android`, or the JVM artifact a KMP library without an Android
         * variant publishes, like `androidx.annotation:annotation` resolved to `annotation-jvm`.
         */
        val jar: MavenMultiplatformLibraryArtifact?,
        val sourceJar: MavenMultiplatformLibraryArtifact?,
    ) : MultiplatformVariant() {
        init {
            require(aar != null || jar != null) { "At least one of aar or jar must be specified" }
            require(aar == null || jar == null) { "Only one of aar or jar must be specified" }
        }

        val artifact: MultiplatformLibraryArtifact =
            aar
                ?: jar
                /* Dead case: The requirements above would ensure that either one is not null */
                ?: throw IllegalStateException()
    }
}
