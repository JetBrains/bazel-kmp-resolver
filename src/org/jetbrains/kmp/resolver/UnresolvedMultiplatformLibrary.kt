package org.jetbrains.kmp.resolver

import java.nio.file.Path
import org.jetbrains.amper.dependency.resolution.DependencyFileImpl
import org.jetbrains.amper.dependency.resolution.HashAlgorithm
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.MavenDependencyNodeWithContext
import org.jetbrains.amper.dependency.resolution.MavenRepository
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope

/**
 * A library of the dependency graph resolved for a single platform, built from its runtime and
 * compile scoped nodes.
 */
internal sealed class UnresolvedMultiplatformLibrary(
    val platform: ResolutionPlatform,
    resolvedNodes: List<MavenDependencyNode>,
    private val repositories: List<MavenRepository>,
    private val substitutions: Substitutions,
    private val kmpParents: KmpParents,
    private val possibleDependencies: Set<SubstitutionId>,
) {
    private val runtimeNode: MavenDependencyNode? =
        resolvedNodes.singleNodeOfScope(ResolutionScope.RUNTIME)
    private val compileNode: MavenDependencyNode? =
        resolvedNodes.singleNodeOfScope(ResolutionScope.COMPILE)
    private val node: MavenDependencyNode =
        runtimeNode ?: compileNode ?: error("must at least have one of compile or runtime node set")

    init {
        require(runtimeNode != null || compileNode != null) {
            "must at least have one of compile or runtime node set"
        }
        require(runtimeNode == null || compileNode == null || runtimeNode.gav == compileNode.gav) {
            """
                runtime and compile scopped nodes are from different coordinates:
                  - ${runtimeNode?.gav}
                  - ${compileNode?.gav}
            """
                .trimIndent()
        }
    }

    /**
     * ID of the KMP umbrella library this node is a platform-specific variant of, or the node's own
     * ID when it is not such a variant: a JVM-only library, or a KMP library publishing its JVM
     * artifact in the umbrella module itself, like `kotlin-stdlib`. The latter makes the JVM
     * variant of `kotlin-stdlib` land under the same library as its wasmJs variant.
     */
    val id: MultiplatformLibraryId by lazy { kmpParents[variantId.toSubstitutionId()] ?: variantId }

    val variantId: MultiplatformLibraryId = node.gav

    protected val files: List<DependencyFileImpl> by lazy {
        node.dependency.files(withSources = true).filterIsInstance<DependencyFileImpl>()
    }

    suspend fun sourceJar(): UnresolvedMultiplatformLibraryArtifact? {
        val sourceJars = files.filter { file -> file.isSourceJar() }
        require(sourceJars.size <= 1) {
            "Expected at most one source jar, found ${sourceJars.size}: $sourceJars"
        }
        return sourceJars.singleOrNull()?.toUnresolvedMultiplatformLibraryArtifact()
    }

    val exportedDependencies: Set<SubstitutionId> =
        compileNode?.platformDependencies() ?: emptySet()

    val dependencies: Set<SubstitutionId> =
        (runtimeNode?.platformDependencies() ?: emptySet()) - exportedDependencies

    /**
     * Dependencies of this node that are libraries of the manifest, identified by the ID of the
     * library they are (or are a platform-specific variant of): a dependency declared directly on a
     * `*-jvm` variant, as POM-only JVM libraries commonly do, must point at the library of that
     * variant. A dependency on a substituted library points at its substitute, resolved in its
     * place.
     */
    private fun MavenDependencyNode.platformDependencies(): Set<SubstitutionId> =
        children
            .asSequence()
            .filterIsInstance<MavenDependencyNode>()
            .map { child ->
                kmpParents[child.gav.toSubstitutionId()]?.toSubstitutionId()
                    ?: child.gav.toSubstitutionId()
            }
            .let { substitutions.substituteSubstitutionIds(it.toList()) }
            .filter { it in possibleDependencies }
            .toSet()

    protected suspend fun DependencyFileImpl.toUnresolvedMultiplatformLibraryArtifact():
        UnresolvedMultiplatformLibraryArtifact {
        val file = this
        return UnresolvedMultiplatformLibraryArtifact(
            fileName = "${file.nameWithoutExtension}.${file.extension}",
            groupId = variantId.groupId,
            artifactId = variantId.artifactId,
            version = variantId.version,
            possibleLocations =
                repositories.map { repository ->
                    file.getUrl(repository, (node as MavenDependencyNodeWithContext).context)
                },
            // Amper will not resolve all hashes, but from the more robust to the least robust, so
            // they are queried in
            // that order. sha1 is the fallback for repositories publishing nothing stronger (see
            // [MultiplatformLibraryArtifactIntegrity.Sha1]);
            // Bazel accepts it in `integrity` strings.
            integrity =
                listOf("sha512", "sha256", "sha1").firstNotNullOfOrNull { algorithm ->
                    file.getExpectedHash(HashAlgorithm(algorithm))?.let { hash ->
                        MultiplatformLibraryArtifactIntegrity.fromHash(algorithm, hash)
                    }
                } ?: error("could not find hash for artifact $file"),
        )
    }

    class Klib(
        platform: ResolutionPlatform,
        resolvedNodes: List<MavenDependencyNode>,
        repositories: List<MavenRepository>,
        substitutions: Substitutions,
        kmpParents: KmpParents,
        possibleDependencies: Set<SubstitutionId>,
    ) :
        UnresolvedMultiplatformLibrary(
            platform,
            resolvedNodes,
            repositories,
            substitutions,
            kmpParents,
            possibleDependencies,
        ) {

        private val klibs: Map<KlibRole, List<DependencyFileImpl>> by lazy {
            files
                .filter { it.isKlib() }
                .groupBy { dependencyFile ->
                    klibRole(variantId.artifactId, dependencyFile.nameWithoutExtension)
                }
                .toSortedMap()
        }

        suspend fun klibs(): Map<KlibRole, List<UnresolvedMultiplatformLibraryArtifact>> {
            return klibs.mapValues { (_, dependencyFiles) ->
                dependencyFiles.map { file -> file.toUnresolvedMultiplatformLibraryArtifact() }
            }
        }

        /**
         * Local path of the klib downloaded by the Amper resolution, allowing the inspection of its
         * contents.
         */
        fun mainKlibPath(): Path {
            val klibs = klibs[KlibRole.MAIN].orEmpty()
            if (klibs.size != 1) {
                error("Expected one main klib. Found ${klibs.size}")
            }
            return klibs.single().path
                ?: error("[$variantId] klib was not downloaded to the local cache")
        }
    }

    class Jvm(
        resolvedNodes: List<MavenDependencyNode>,
        repositories: List<MavenRepository>,
        substitutions: Substitutions,
        kmpParents: KmpParents,
        possibleDependencies: Set<SubstitutionId>,
    ) :
        UnresolvedMultiplatformLibrary(
            ResolutionPlatform.JVM,
            resolvedNodes,
            repositories,
            substitutions,
            kmpParents,
            possibleDependencies,
        ) {
        suspend fun jar(): UnresolvedMultiplatformLibraryArtifact {
            val jars = files.filter { file -> file.isClasspathJar() }
            require(jars.size == 1) {
                "[$variantId] node must have exactly one jar, found ${jars.size}: ${jars.map { "${it.nameWithoutExtension}.${it.extension}" }}"
            }
            return jars.single().toUnresolvedMultiplatformLibraryArtifact()
        }
    }
}

internal data class UnresolvedMultiplatformLibraryArtifact(
    val fileName: String,
    val integrity: MultiplatformLibraryArtifactIntegrity,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val possibleLocations: List<String>,
)

/**
 * The node of the given [scope] among nodes sharing the same resolved coordinates.
 *
 * Several nodes of the graph can request the same library at different versions (common on JVM,
 * where POMs declare all kinds of `kotlin-stdlib` versions), but conflict resolution makes them
 * point at the same dependency, hence the same files and children, so they collapse into one.
 */
private fun List<MavenDependencyNode>.singleNodeOfScope(
    scope: ResolutionScope,
): MavenDependencyNode? {
    val scopeNodes = filter {
        it.dependency.resolutionConfig.scope == scope
    }
        .distinctBy { it.dependency }
    require(scopeNodes.size <= 1) {
        "UnresolvedMultiplatformLibrary cannot have multiple $scope scope instance, but got $scopeNodes"
    }
    return scopeNodes.singleOrNull()
}
