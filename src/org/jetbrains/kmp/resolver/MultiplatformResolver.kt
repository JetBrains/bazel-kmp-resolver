package org.jetbrains.kmp.resolver

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import org.jetbrains.amper.dependency.resolution.*
import org.jetbrains.amper.dependency.resolution.diagnostics.Message
import org.jetbrains.amper.dependency.resolution.diagnostics.Severity
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path

internal typealias MultiplatformLibraryId = String

@Serializable
internal data class MultiplatformLibrary(
    val id: MultiplatformLibraryId,
    /**
     * ID of the resolved multiplatform library, usually [id] would point to the umbrella library like `kotlin-stdlib`,
     * while [variantId] would point to the variant library like `kotlin-stdlib-wasm-js`.
     */
    val variantId: MultiplatformLibraryId,

    /**
     * .klib of this imported dependency, exposed to the compile library path of direct dependents.
     */
    val klib: MultiplatformLibraryArtifact,
    val sourceJar: MultiplatformLibraryArtifact?,

    /**
     * Dependencies of this library, exposed to the link path of dependents transitively.
     */
    val dependencies: List<MultiplatformLibraryId>,

    /**
     * Dependencies of this library, exposed to the link path of dependents transitively, and exposed to the compile library path of *direct* dependents.
     */
    val exportedDependencies: List<MultiplatformLibraryId>,
)

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
    private val artifactResolver: ArtifactUrlResolver,
) {
    val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val amperCachePath: Path = cachePath.resolve("amper-cache")

    internal suspend fun resolve(coordinatesBag: Collection<String>): List<MultiplatformLibrary> {
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
        val collectionResult = collectNodes(root)
        logger.debug("Collecting artifacts of nodes of the dependency graph...")
        val nodesWithUnresolvedArtifacts = collectArtifacts(collectionResult)
        logger.debug("Resolving artifacts of nodes of the dependency graph against the specified repositories...")
        val resolvedNodes = resolveArtifacts(nodesWithUnresolvedArtifacts)
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

    private fun collectNodes(root: RootDependencyNode): CollectionResult {
        var errored = false

        val errors = root.resolutionErrors(logger)
        if (errors.isNotEmpty()) {
            errored = true
            logger.error(errors.toErrorLog())
        }

        val resolved = mutableMapOf<MultiplatformLibraryId, List<MavenDependencyNode>>()
        val visited = mutableSetOf<MavenDependencyNode>()
        val skipped = mutableSetOf<MavenDependencyNode>()
        val queue = ArrayDeque<MavenDependencyNode>()
        queue.addAll(root.children.filterIsInstance<MavenDependencyNode>())
        while (queue.isNotEmpty()) {
            val node = queue.removeFirstOrNull()

            when {
                node == null -> {}
                visited.contains(node) -> {}
                else -> {
                    visited.add(node)
                    val errors = node.resolutionErrors(logger)
                    when {
                        errors.isNotEmpty() -> {
                            errored = true
                            logger.error("[${node.idForBazel}] ${errors.toErrorLog()}")
                        }

                        node.isBom -> {
                            skipped.add(node)
                            logger.info("[${node.idForBazel}] skipping, bom")
                        }

                        else -> {
                            logger.debug("[${node.idForBazel}] Processing node")
                            when (val actualNode = node.actualMavenDependencyOfVariantsMatching { it.klib() }) {
                                null -> {
                                    skipped.add(node)
                                    logger.warn("[${node.idForBazel}] skipping, no WasmJS variant found")
                                }

                                else -> {
                                    logger.info("[${node.idForBazel}] resolved to ${actualNode.idForBazel}")
                                    val actualNodeErrors = actualNode.resolutionErrors(logger)
                                    when {
                                        actualNodeErrors.isNotEmpty() -> {
                                            errored = true
                                            logger.error("[${actualNode.idForBazel}] ${actualNodeErrors.toErrorLog()}")
                                        }

                                        else -> {
                                            val children = actualNode.children.filterIsInstance<MavenDependencyNode>()
                                            queue.addAll(children)

                                            val existing = resolved[node.idForBazel] ?: emptyList()
                                            resolved[node.idForBazel] = existing + actualNode
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return CollectionResult(
            nodes = resolved,
            skipped = skipped.map { it.idForBazel }.toSet(),
            errored = errored,
        )
    }


    private suspend fun collectArtifacts(collectionResult: CollectionResult): List<NodeWithUnresolvedArtifacts> = when {
        collectionResult.errored -> error("failed to resolve coordinates, check error logs above.")
        else -> collectionResult.nodes.map { (id, nodes) ->
            val variantIds = nodes.map { it.idForBazel }.toSet()
            require(variantIds.size == 1) { "$id must match exactly one variant, got $variantIds" }
            val variantId = variantIds.first()

            val klibs = nodes.flatMap { it.filesMatching(logger) { it.klib() } }.toSet()
            logger.info("[$id] found klibs: $klibs")
            val klib = klibs.singleOrNull() ?: error("Expected exactly one klib for dependency $id, got: $klibs")

            val sourceJars = nodes.flatMap { it.filesMatching(logger) { it.sourceJar() } }.toSet()
            logger.info("[$id] found sourceJars: $sourceJars")
            require(sourceJars.size <= 1) { "Expected at most one source jar, found ${sourceJars.size}: $sourceJars" }
            val sourceJar = sourceJars.singleOrNull()

            val children = nodes.flatMap { it.children }.filterIsInstance<MavenDependencyNode>().distinct()
            val (runtimeDeps, compileDeps) = children.filter { child ->
                child.idForBazel !in collectionResult.skipped
            }.partition {
                it.dependency.resolutionConfig.scope == ResolutionScope.RUNTIME
            }

            val runtimeDepsIds = runtimeDeps.map { it.idForBazel }.toSet()
            val compileDepsIds = compileDeps.map { it.idForBazel }.toSet()
            NodeWithUnresolvedArtifacts(
                id = id,
                variantId = variantId,
                klib = klib,
                sourceJar = sourceJar,
                dependencies = (runtimeDepsIds - compileDepsIds).sorted(),
                exportedDependencies = compileDepsIds.sorted(),
            )
        }
    }

    private suspend fun resolveArtifacts(nodesWithUnresolvedArtifacts: List<NodeWithUnresolvedArtifacts>): List<MultiplatformLibrary> =
        coroutineScope {
            nodesWithUnresolvedArtifacts.map { unresolvedNode ->
                async {
                    unresolvedNode.resolve(repositories, artifactResolver)
                }
            }.awaitAll()
        }
}

private data class CollectionResult(
    val nodes: Map<MultiplatformLibraryId, List<MavenDependencyNode>>,
    val skipped: Set<MultiplatformLibraryId>,
    val errored: Boolean,
)

private val MavenDependencyNode.idForBazel get(): MultiplatformLibraryId = "$group:$module:${resolvedVersion().orUnspecified()}"

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

private fun List<Message>.toErrorLog(): String {
    return buildString {
        if (this@toErrorLog.isNotEmpty()) {
            appendLine("resolution errors:")
            this@toErrorLog.forEach { error -> appendLine("- ${error.message} (${error.details ?: ""})") }
        }
    }
}

private fun Map<String, String>.sourceJar(): Boolean {
    return this["org.gradle.category"] == "documentation" && this["org.gradle.docstype"] == "sources" && this["org.jetbrains.kotlin.platform.type"] == "wasm" && this["org.jetbrains.kotlin.wasm.target"] == "js"
}

private fun Map<String, String>.klib(): Boolean {
    return this["org.gradle.category"] == "library" && this["org.gradle.usage"] in setOf(
        "kotlin-api", "kotlin-runtime"
    ) && this["org.jetbrains.kotlin.platform.type"] == "wasm" && this["org.jetbrains.kotlin.wasm.target"] == "js"
}

@Suppress("INVISIBLE_REFERENCE")
private fun MavenDependencyNode.actualMavenDependencyOfVariantsMatching(
    attributeMatcher: (Map<String, String>) -> Boolean,
): MavenDependencyNode? {
    val originalDep = this.dependency as MavenDependencyImpl
    val availableAts = originalDep.variants.filter { attributeMatcher(it.attributes) }.map { it.`available-at` }.toSet()
    return when {
        availableAts.isEmpty() -> null // node variants are not matching the [attributeMatcher]
        availableAts.size > 1 -> error("all matched variants must point to the same Maven dependency, but got: ${originalDep.variants}")
        availableAts.all { it == null } -> this // no variant indirection, it means that dependency is the actual one we need to consider
        else -> { // all variants are pointing to the same Maven dependency, e.g. the `-wasm-js` one, that's the one we must consider here
            val availableAt = availableAts.single()
            children.filterIsInstance<MavenDependencyNode>().first {
                it.group == availableAt.group && it.module == availableAt.module && it.dependency.version == availableAt.version
            }
        }
    }
}

@Suppress("INVISIBLE_REFERENCE")
private suspend fun MavenDependencyNode.filesMatching(
    logger: Logger,
    attributeMatcher: (Map<String, String>) -> Boolean,
): List<UnresolvedMultiplatformLibraryArtifact> {
    logger.debug("[${idForBazel}] considering variants")
    val dep = this.dependency as MavenDependencyImpl
    require(dep.variants.isNotEmpty()) {
        error("no variants found for dependency $idForBazel")
    }
    return dep.variants.filter { variant ->
        logger.debug("[${idForBazel}] considering variant with attributes -> ${variant.attributes}")
        attributeMatcher(variant.attributes)
    }.flatMap { variant -> variant.files }.map { file ->
        val version = resolvedVersion() ?: error("could not resolve version for dependency: $dependency")

        val groupPath = group.split('.').joinToString("/")
        val artifactPath = "${groupPath}/${module}/${version}/${file.url.removePrefix("./")}"

        UnresolvedMultiplatformLibraryArtifact(
            sha256checksum = file.sha256,
            groupId = group,
            artifactId = module,
            version = version,
            artifactPath = artifactPath,
        )
    }
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
