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
import kotlin.io.path.absolutePathString

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
        logger.debug("Resolving dependency graph...")
        logger.info("Cache location: ${amperCachePath.absolutePathString()}")
        logger.info("Against repositories:\n${repositories.joinToString("\n") { "  ${it.url}" }}")
        if (substitutions.isNotEmpty()) {
            logger.info("Using substitutions:\n${substitutions.entries.joinToString("\n") { "  ${it.key} -> ${it.value.gav}" }}")
        }
        logger.info("Coordinates:\n${coordinatesBag.joinToString("\n") { "  $it" }}")
        val root = callAmperResolution(
            coordinatesBag = coordinatesBag,
            platforms = setOf(ResolutionPlatform.WASM_JS),
            scope = listOf(
                ResolutionScope.RUNTIME,
                ResolutionScope.COMPILE,
            ),
        )
        logger.debug("Collecting nodes of the dependency graph...")
        val nodes = collectNodes(root) { node ->
            val hasKmpParent = node.getParentKmpLibraryCoordinates() != null
            val hasRelevantArtifacts = (node.dependency as MavenDependencyImpl).files(withSources = true).any {
                it.extension == "klib" || (it.extension == "jar" && it.nameWithoutExtension.endsWith("-sources"))
            }
            hasKmpParent && hasRelevantArtifacts
        }
        logger.debug("Resolving artifacts of nodes of the dependency graph against the specified repositories...")
        return resolveArtifacts(nodes)
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

        val resolver = Resolver()
        resolver.resolveDependencies(
            root = root,
            resolutionLevel = ResolutionLevel.NETWORK,
            transitive = true,
            downloadSources = true,
            incrementalCacheUsage = IncrementalCacheUsage.SKIP,
            unspecifiedVersionResolver = MavenDependencyUnspecifiedVersionResolverBase(),
        )

        return root
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

        val filteredNodes = nodes.filter(collectionFilter)

        // some KMP dependencies are incorrectly declaring a JVM dependency
        // e.g. `ai.jetbrains.code.files:code-files-model-wasm-js:1.0.0-beta.170` which depends on `org.slf4j:slf4j-api:2.0.17`
        // a purely JVM dependency
        val possibleDependencies = filteredNodes.flatMap { node ->
            val parent = node.getParentKmpLibraryCoordinates()
            listOfNotNull(
                node.gav.toSubstitutionId(),
                parent?.let { p -> substitutionId(p.groupId, p.artifactId) },
            )
        }.toSet()
        return filteredNodes.groupBy { it.gav }.map { (_, scoppedNodes) ->
            UnresolvedMultiplatformLibrary.WasmJs(
                resolvedNodes = scoppedNodes,
                substitutions = substitutions,
                possibleDependencies = possibleDependencies,
                repositories = repositories,
            )
        }
    }

    private suspend fun resolveArtifacts(nodes: List<UnresolvedMultiplatformLibrary>): List<MultiplatformVariant> =
        coroutineScope {
            nodes.map { unresolvedNode ->
                when (unresolvedNode) {
                    is UnresolvedMultiplatformLibrary.WasmJs -> async {
                        unresolvedNode.resolve(artifactResolver)
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

// TODO: both of these are not necessarily true when resolving a dependency against many platforms
private fun DependencyFileImpl.isSourceJar(): Boolean = extension == "jar" && nameWithoutExtension.endsWith("-sources")
private fun DependencyFile.isWasmKlib(): Boolean = extension == "klib"

private data class UnresolvedMultiplatformLibraryArtifact(
    val fileName: String,
    val sha256checksum: String,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val possibleLocations: List<UnresolvedMultiplatformLibraryArtifactLocation>,
)

private data class UnresolvedMultiplatformLibraryArtifactLocation(
    val url: String,
    val credentials: RepositoryCredentials,
)

private sealed class UnresolvedMultiplatformLibrary {
    abstract val id: MultiplatformLibraryId
    abstract val variantId: MultiplatformLibraryId
    abstract val dependencies: Set<SubstitutionId>
    abstract val exportedDependencies: Set<SubstitutionId>

    data class WasmJs(
        private val resolvedNodes: List<MavenDependencyNode>,
        private val repositories: List<MavenRepository>,
        private val substitutions: Substitutions,
        private val possibleDependencies: Set<SubstitutionId>,
    ) : UnresolvedMultiplatformLibrary() {
        private val runtimeNode: MavenDependencyNode? =
            resolvedNodes.filter { it.dependency.resolutionConfig.scope == ResolutionScope.RUNTIME }
                .let { runtimeNodes ->
                    require(runtimeNodes.size <= 1) { "UnresolvedMultiplatformLibrary cannot have multiple runtime scope instance, but got $runtimeNodes" }
                    runtimeNodes.singleOrNull()
                }
        private val compileNode: MavenDependencyNode? =
            resolvedNodes.filter { it.dependency.resolutionConfig.scope == ResolutionScope.COMPILE }
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
            // TODO: could we do something about this super hacky substitution resolution?
            substitutions.substituteMultiplatformLibraryIds(listOf(parentId)).single()
        }
        private val originalId: MultiplatformLibraryId = node.gav

        // TODO: could we do something about this super hacky substitution resolution?
        override val variantId: MultiplatformLibraryId =
            substitutions.substituteMultiplatformLibraryIds(listOf(originalId)).single()

        private val files: List<DependencyFileImpl> by lazy {
            node.dependency.files(withSources = true).filterIsInstance<DependencyFileImpl>()
        }

        suspend fun sourceJar(): UnresolvedMultiplatformLibraryArtifact? {
            val sourceJars = files.filter { file -> file.isSourceJar() }
            require(sourceJars.size <= 1) { "Expected at most one source jar, found ${sourceJars.size}: $sourceJars" }
            return sourceJars.singleOrNull()?.toUnresolvedMultiplatformLibraryArtifact()
        }

        suspend fun klib(): UnresolvedMultiplatformLibraryArtifact {
            val klibs = files.filter { file -> file.isWasmKlib() }
            require(klibs.isNotEmpty()) { "[$variantId] node must have at least one klib" }
            return klibs.single().toUnresolvedMultiplatformLibraryArtifact()
        }

        // TODO: could we do something about this super hacky substitution resolution?
        override val exportedDependencies: Set<SubstitutionId> =
            substitutions.substituteSubstitutionIds(compileNode?.wasmJsDependencies() ?: emptySet())

        // TODO: could we do something about this super hacky substitution resolution?
        override val dependencies: Set<SubstitutionId> = substitutions.substituteSubstitutionIds(
            runtimeNode?.wasmJsDependencies() ?: emptySet()
        ) - exportedDependencies

        private fun MavenDependencyNode.wasmJsDependencies(): Set<SubstitutionId> =
            children.asSequence().filterIsInstance<MavenDependencyNode>().map { it.gav.toSubstitutionId() }
                .filter { it in possibleDependencies }.toSet()

        private suspend fun DependencyFileImpl.toUnresolvedMultiplatformLibraryArtifact(): UnresolvedMultiplatformLibraryArtifact {
            val file = this
            return UnresolvedMultiplatformLibraryArtifact(
                fileName = "${file.nameWithoutExtension}.${file.extension}",
                groupId = variantId.groupId,
                artifactId = variantId.artifactId,
                version = variantId.version,
                possibleLocations = repositories.map { repository ->
                    val url = file.getUrl(repository, (node as MavenDependencyNodeWithContext).context)
                        // TODO: could we do something about this super hacky substitution resolution?
                        .replace(originalId.groupId.replace(".", "/"), variantId.groupId.replace(".", "/"))
                        .replace(originalId.artifactId, variantId.artifactId)
                        .replace(originalId.version, variantId.version)
                    UnresolvedMultiplatformLibraryArtifactLocation(
                        url = url,
                        credentials = RepositoryCredentials(
                            repositoryUrl = repository.url,
                            username = repository.userName,
                            password = repository.password,
                        ),
                    )
                },
                sha256checksum = file.getExpectedHash(HashAlgorithm("sha256"))
                    ?: error("could not find hash for artifact $file")
            )
        }
    }
}

private suspend fun UnresolvedMultiplatformLibraryArtifact.resolve(
    artifactUrlResolver: ArtifactUrlResolver,
): MultiplatformLibraryArtifact {
    val urls = coroutineScope {
        possibleLocations.map { location ->
            async { artifactUrlResolver.artifactExistsAt(location.url, location.credentials) }
        }.awaitAll().filterIsInstance<ArtifactFile.Resolved>().map { it.url }
    }
    require(urls.isNotEmpty()) { "[$groupId:$artifactId:$version] artifact could not be find in any of the specified repositories" }

    return MultiplatformLibraryArtifact(
        sha256checksum = sha256checksum,
        groupId = groupId,
        artifactId = artifactId,
        version = version,
        urls = urls,
    )
}

private suspend fun UnresolvedMultiplatformLibrary.WasmJs.resolve(
    artifactUrlResolver: ArtifactUrlResolver,
): MultiplatformVariant.WasmJs = coroutineScope {
    val resolvedKlib = async { klib().resolve(artifactUrlResolver) }
    val resolvedSourceJar = sourceJar()?.let { async { it.resolve(artifactUrlResolver) } }
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

private fun MultiplatformLibraryId.toSubstitutionId(): SubstitutionId = substitutionId(groupId, artifactId)
private fun substitutionId(groupId: String, artifactId: String) = "$groupId:$artifactId"

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