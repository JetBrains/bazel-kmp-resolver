package org.jetbrains.kmp.resolver

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.jetbrains.amper.dependency.resolution.*
import org.jetbrains.amper.dependency.resolution.diagnostics.Message
import org.jetbrains.amper.dependency.resolution.diagnostics.Severity
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Substitution ID represents a groupId:artifactId string
 */
internal typealias SubstitutionId = String
internal typealias Substitutions = Map<SubstitutionId, MultiplatformLibraryId>

@Serializable(with = MultiplatformLibraryIdSerializer::class)
internal data class MultiplatformLibraryId(
    val groupId: String,
    val artifactId: String,
    val version: String,
) : Comparable<MultiplatformLibraryId> {
    val gav: String = "${groupId}:${artifactId}:${version}"
    override fun compareTo(other: MultiplatformLibraryId): Int = gav.compareTo(other.gav)

    companion object {
        fun fromString(s: String): MultiplatformLibraryId {
            val (groupId, artifactId, version) = s.split(":", limit = 3)
            return MultiplatformLibraryId(groupId, artifactId, version)
        }
    }
}

internal object MultiplatformLibraryIdSerializer : KSerializer<MultiplatformLibraryId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("MultiplatformLibraryId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: MultiplatformLibraryId) = encoder.encodeString(value.gav)
    override fun deserialize(decoder: Decoder): MultiplatformLibraryId =
        MultiplatformLibraryId.fromString(decoder.decodeString())
}

@Serializable
internal data class MultiplatformLibrary(
    val id: MultiplatformLibraryId,
    val variants: List<MultiplatformVariant>,
)

@Serializable
internal sealed class MultiplatformVariant {
    /**
     * ID of the parent KMP umbrella library from which this variant is from.
     */
    abstract val id: MultiplatformLibraryId

    /**
     * ID of the resolved multiplatform library, usually [id] would point to the umbrella library like `kotlin-stdlib`,
     * while [variantId] would point to the variant library like `kotlin-stdlib-wasm-js`.
     */
    abstract val variantId: MultiplatformLibraryId

    /**
     * Dependencies of this library, exposed to the link path of dependents transitively.
     */
    abstract val dependencies: List<SubstitutionId>

    /**
     * Dependencies of this library, exposed to the link path of dependents transitively, and exposed to the compile library path of *direct* dependents.
     */
    abstract val exportedDependencies: List<SubstitutionId>

    @Serializable
    @SerialName("wasmjs")
    data class WasmJs(
        override val id: MultiplatformLibraryId,
        override val variantId: MultiplatformLibraryId,
        override val dependencies: List<SubstitutionId>,
        override val exportedDependencies: List<SubstitutionId>,

        /**
         * .klib of this imported dependency, exposed to the compile library path of direct dependents.
         */
        val klib: MultiplatformLibraryArtifact,
        val sourceJar: MultiplatformLibraryArtifact?,
    ) : MultiplatformVariant()
}

@Serializable
internal data class MultiplatformLibraryArtifact(
    val sha256checksum: String?,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val urls: List<String>,
)

internal class MultiplatformResolver(
    cachePath: Path,
    private val repositories: List<MavenRepository>,
    private val substitutions: Substitutions,
    private val artifactResolver: ArtifactUrlResolver,
) {
    val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val amperCachePath: Path = cachePath.resolve("amper-cache")

    internal suspend fun resolve(coordinatesBag: Collection<String>): List<MultiplatformVariant> {
        logger.info("Resolving dependency graph for:\n${coordinatesBag.joinToString("\n") { " - $it" }}")
        val root = callAmperResolution(
            coordinatesBag = coordinatesBag,
            platforms = setOf(ResolutionPlatform.WASM_JS),
            scope = listOf(
                ResolutionScope.RUNTIME,
                ResolutionScope.COMPILE,
            ),
        )
        logger.debug("Collecting nodes of the dependency graph...")
        val nodes = collectWasmJsTargetNodes(root)
        logger.debug("Resolving artifacts of nodes of the dependency graph against the specified repositories...")
        val resolvedNodes = resolveArtifacts(nodes)
        return resolvedNodes
    }

    private suspend fun callAmperResolution(
        coordinatesBag: Collection<String>,
        platforms: Set<ResolutionPlatform>,
        scope: List<ResolutionScope>,
    ): RootDependencyNodeWithContext {
        val defaultSettings: SettingsBuilder.() -> Unit = {
            this.platforms = platforms
            repositories = this@MultiplatformResolver.repositories
            cache = getDefaultFileCacheBuilder(amperCachePath)
        }
        val templateContext = Context {
            defaultSettings()
        }
        val contexts = scope.map {
            Context {
                defaultSettings()
                this.scope = it
            }
        }
        val root = RootDependencyNodeWithContext(
            templateContext = templateContext,
            children = coordinatesBag.flatMap { c -> contexts.map { c.toMavenNode(it) } },
        )

        Resolver().resolveDependencies(
            root = root,
            resolutionLevel = ResolutionLevel.NETWORK,
            transitive = true,
            incrementalCacheUsage = IncrementalCacheUsage.SKIP,
            unspecifiedVersionResolver = MavenDependencyUnspecifiedVersionResolverBase(),
        )
        return root
    }

    @Suppress("INVISIBLE_REFERENCE")
    private fun collectWasmJsTargetNodes(root: RootDependencyNode) = collectNodes(root) { node ->
        val variants = (node.dependency as MavenDependencyImpl).variants
        variants.any { it.files.isNotEmpty() && (it.attributes.wasmKlib() || it.attributes.wasmSources()) }
    }

    private fun collectNodes(
        root: RootDependencyNode,
        collectionFilter: (variant: MavenDependencyNode) -> Boolean,
    ): List<UnresolvedMultiplatformLibrary> {
        val nodes = root.distinctBfsSequence().filterIsInstance<MavenDependencyNode>()
        val errorsByNode = nodes.map { node ->
            node.gav to node.resolutionErrors(logger)
        }.filter { (_, errors) ->
            errors.isNotEmpty()
        }.toList()
        require(errorsByNode.isEmpty()) {
            buildString {
                appendLine("Amper resolution failed with errors:")
                errorsByNode.forEach { (gav, errors) ->
                    appendLine("\t[$gav] errors:")
                    errors.forEach { error -> appendLine("\t\t${error.message} (${error.details ?: ""})") }
                }
            }
        }
        return nodes.filter(collectionFilter).groupBy { it.gav }.map { (_, scoppedNodes) ->
            UnresolvedMultiplatformLibrary.WasmJs(resolvedNodes = scoppedNodes, substitutions = substitutions)
        }
    }

    private suspend fun resolveArtifacts(nodes: List<UnresolvedMultiplatformLibrary>): List<MultiplatformVariant> =
        coroutineScope {
            nodes.map { unresolvedNode ->
                when (unresolvedNode) {
                    is UnresolvedMultiplatformLibrary.WasmJs -> async {
                        unresolvedNode.resolve(repositories, artifactResolver)
                    }
                }
            }.awaitAll()
        }
}

private val MavenDependencyNode.gav
    get(): MultiplatformLibraryId = MultiplatformLibraryId(
        groupId = group,
        artifactId = module,
        version = resolvedVersion().orUnspecified(),
    )

private fun DependencyNode.resolutionErrors(logger: Logger): List<Message> =
    messages.filter { it.severity >= Severity.ERROR }.mapNotNull {
        when {
            it.message.contains("Platform wasmJs is not supported by the library") -> {
                logger.warn("[${it.id}] skipping wasmJs platform")
                null
            }

            else -> it
        }
    }

private fun Map<String, String>.wasmSources(): Boolean {
    return this["org.gradle.category"] == "documentation" && this["org.gradle.docstype"] == "sources" && this["org.jetbrains.kotlin.platform.type"] == "wasm" && this["org.jetbrains.kotlin.wasm.target"] == "js"
}

private fun Map<String, String>.wasmKlib(): Boolean {
    return this["org.gradle.category"] == "library" && this["org.gradle.usage"] in setOf(
        "kotlin-api", "kotlin-runtime"
    ) && this["org.jetbrains.kotlin.platform.type"] == "wasm" && this["org.jetbrains.kotlin.wasm.target"] == "js"
}

private data class UnresolvedMultiplatformLibraryArtifact(
    val sha256checksum: String?,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val artifactPath: String,
)

private sealed class UnresolvedMultiplatformLibrary {
    abstract val id: MultiplatformLibraryId
    abstract val variantId: MultiplatformLibraryId
    abstract val dependencies: Set<SubstitutionId>
    abstract val exportedDependencies: Set<SubstitutionId>

    data class WasmJs(
        private val resolvedNodes: List<MavenDependencyNode>,
        private val substitutions: Substitutions,
    ) : UnresolvedMultiplatformLibrary() {
        private val runtimeNode: MavenDependencyNode? =
            resolvedNodes.filter { it.dependency.resolutionConfig.scope == ResolutionScope.RUNTIME }.distinct()
                .let { runtimeNodes ->
                    require(runtimeNodes.size <= 1) { "UnresolvedMultiplatformLibrary cannot have multiple runtime scope instance, but got $runtimeNodes" }
                    runtimeNodes.singleOrNull()
                }
        private val compileNode: MavenDependencyNode? =
            resolvedNodes.filter { it.dependency.resolutionConfig.scope == ResolutionScope.COMPILE }.distinct()
                .let { compileNodes ->
                    require(compileNodes.size <= 1) { "UnresolvedMultiplatformLibrary cannot have multiple compile scope instance, but got $compileNodes" }
                    compileNodes.singleOrNull()
                }
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
                """.trimIndent()
            }
        }

        override val id: MultiplatformLibraryId by lazy {
            val parent = node.getParentKmpLibraryCoordinates() ?: error("$node must have a KMP parent library")
            val parentId = MultiplatformLibraryId(
                groupId = parent.groupId,
                artifactId = parent.artifactId,
                version = parent.version ?: error("$node parent $parent must have a version"),
            )
            substitutions.substituteMultiplatformLibraryIds(listOf(parentId)).single()
        }
        private val originalId: MultiplatformLibraryId = node.gav
        override val variantId: MultiplatformLibraryId =
            substitutions.substituteMultiplatformLibraryIds(listOf(originalId)).single()

        @Suppress("INVISIBLE_REFERENCE")
        private val sourceJarVariant: org.jetbrains.amper.dependency.resolution.metadata.json.module.Variant? =
            node.variantMatching { it.wasmSources() }.distinct().let { sourceJars ->
                require(sourceJars.size <= 1) { "Expected at most one source jar, found ${sourceJars.size}: $sourceJars" }
                sourceJars.singleOrNull()
            }

        @Suppress("INVISIBLE_REFERENCE")
        private val klibVariant: org.jetbrains.amper.dependency.resolution.metadata.json.module.Variant =
            node.variantMatching { it.wasmKlib() }.distinct().singleOrNull()
                ?: error("[$variantId] node must have exactly one klib")

        val sourceJar: UnresolvedMultiplatformLibraryArtifact? by lazy {
            when (sourceJarVariant) {
                null -> null
                else -> {
                    val sourceJars = extractFilesFromVariant(sourceJarVariant)
                    require(sourceJars.size <= 1) { "Expected at most one source jar, found ${sourceJars.size}: $sourceJars" }
                    sourceJars.singleOrNull()
                }
            }
        }
        val klib: UnresolvedMultiplatformLibraryArtifact by lazy {
            val klibs = extractFilesFromVariant(klibVariant)
            require(klibs.isNotEmpty()) { "[$variantId] node must have at least one klib" }
            klibs.single()
        }

        @Suppress("INVISIBLE_REFERENCE")
        override val exportedDependencies: Set<SubstitutionId> =
            substitutions.substituteSubstitutionIds(compileNode?.wasmJsDependencies() ?: emptySet())

        @Suppress("INVISIBLE_REFERENCE")
        override val dependencies: Set<SubstitutionId> = substitutions.substituteSubstitutionIds(
            runtimeNode?.wasmJsDependencies() ?: emptySet()
        ) - exportedDependencies

        @Suppress("INVISIBLE_REFERENCE")
        private fun extractFilesFromVariant(v: org.jetbrains.amper.dependency.resolution.metadata.json.module.Variant): List<UnresolvedMultiplatformLibraryArtifact> =
            v.files.map { file ->
                val groupPath = variantId.groupId.split('.').joinToString("/")
                // @formatter:off
                val fileName = file.url
                    .replace(originalId.artifactId, variantId.artifactId)
                    .replace(originalId.version, variantId.version)
                    .removePrefix("./")
                // @formatter:on
                val artifactPath = "$groupPath/${variantId.artifactId}/${variantId.version}/$fileName"

                UnresolvedMultiplatformLibraryArtifact(
                    sha256checksum = file.sha256,
                    groupId = variantId.groupId,
                    artifactId = variantId.artifactId,
                    version = variantId.version,
                    artifactPath = artifactPath,
                )
            }

        @Suppress("INVISIBLE_REFERENCE")
        private fun MavenDependencyNode.wasmJsDependencies(): Set<SubstitutionId> =
            variantMatching { it.wasmKlib() }.singleOrNull()?.dependencies?.map { it.ga }?.toSet() ?: emptySet()
    }
}

private val MavenDependencyNode.scoppedId: String get() = "${dependency.resolutionConfig.scope}|${gav.gav}"

@Suppress("INVISIBLE_REFERENCE")
private val org.jetbrains.amper.dependency.resolution.metadata.json.module.Dependency.ga: SubstitutionId get() = "$group:$module"

@Suppress("INVISIBLE_REFERENCE")
private fun MavenDependencyNode.variantMatching(attributeMatcher: (Map<String, String>) -> Boolean): List<org.jetbrains.amper.dependency.resolution.metadata.json.module.Variant> {
    val dep = this.dependency as MavenDependencyImpl
    require(dep.variants.isNotEmpty()) { "no variants found for dependency $gav" }
    return dep.variants.filter { variant -> attributeMatcher(variant.attributes) }
}

private suspend fun UnresolvedMultiplatformLibraryArtifact.resolve(
    repositories: List<MavenRepository>,
    artifactUrlResolver: ArtifactUrlResolver,
): MultiplatformLibraryArtifact {
    val urls = coroutineScope {
        repositories.map {
            async { artifactUrlResolver.artifactExistsAt(it, artifactPath) }
        }.awaitAll().filterIsInstance<ArtifactFile.Resolved>().map { it.url }
    }
    require(urls.isNotEmpty()) { "No URLs found for artifact $artifactPath" }

    return MultiplatformLibraryArtifact(
        sha256checksum = sha256checksum,
        groupId = groupId,
        artifactId = artifactId,
        version = version,
        urls = urls,
    )
}

private suspend fun UnresolvedMultiplatformLibrary.WasmJs.resolve(
    repositories: List<MavenRepository>,
    artifactUrlResolver: ArtifactUrlResolver,
): MultiplatformVariant.WasmJs = coroutineScope {
    val resolvedKlib = async { klib.resolve(repositories, artifactUrlResolver) }
    val resolvedSourceJar = sourceJar?.let { async { it.resolve(repositories, artifactUrlResolver) } }
    MultiplatformVariant.WasmJs(
        id = id,
        variantId = variantId,
        klib = resolvedKlib.await(),
        sourceJar = resolvedSourceJar?.await(),
        dependencies = dependencies.sorted(),
        exportedDependencies = exportedDependencies.sorted(),
    )
}

private fun String.toMavenNode(context: Context): MavenDependencyNodeWithContext {
    val isBom = startsWith("bom:")
    val parts = removePrefix("bom:").trim().split(":")
    val group = parts[0]
    val module = parts[1]
    val version = when {
        parts.size > 2 -> parts[2]
        else -> null
    }
    return context.toMavenDependencyNode(
        coordinates = MavenCoordinates(
            groupId = group,
            artifactId = module,
            version = version,
        ),
        isBom = isBom,
    )
}

private fun MultiplatformLibraryId.toSubstitutionId(): SubstitutionId = "$groupId:$artifactId"

private fun Substitutions.substituteMultiplatformLibraryIds(originalIds: Collection<MultiplatformLibraryId>): Set<MultiplatformLibraryId> =
    originalIds.map { originalId ->
        when (val subId = this[originalId.toSubstitutionId()]) {
            null -> originalId
            else -> subId
        }
    }.toSet()

private fun Substitutions.substituteSubstitutionIds(originalIds: Collection<SubstitutionId>): Set<SubstitutionId> =
    originalIds.map { originalId ->
        when (val subId = this[originalId]) {
            null -> originalId
            else -> subId.toSubstitutionId()
        }
    }.toSet()