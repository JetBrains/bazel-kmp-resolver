package org.jetbrains.kmp.resolver

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import org.jetbrains.amper.dependency.resolution.*
import org.jetbrains.amper.dependency.resolution.diagnostics.Message
import org.jetbrains.amper.dependency.resolution.diagnostics.Severity
import org.jetbrains.amper.dependency.resolution.diagnostics.detailedMessage
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.name

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
) {
    val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val amperCachePath: Path = cachePath.resolve("amper-cache")

    internal suspend fun resolveMultiplatformComponentsOf(coordinatesBag: Collection<String>): List<MultiplatformLibrary> {
        logger.info("Resolving dependency graph for:\n${coordinatesBag.joinToString("\n") { " - $it" }}")
        val nodes = resolveNodes(coordinatesBag)
        return ArtifactUrlResolver().use { artifactUrlResolver ->
            coroutineScope {
                nodes.values.map { unresolvedNode ->
                    async { unresolvedNode.resolve(repositories, artifactUrlResolver) }
                }.awaitAll()
            }
        }
    }

    private suspend fun resolveNodes(coordinatesBag: Collection<String>): Map<String, UnresolvedNode> {
        val platforms = setOf(ResolutionPlatform.WASM_JS)
        val defaultSettings: SettingsBuilder.() -> Unit = {
            this.platforms = platforms
            repositories = this@MultiplatformResolver.repositories
            cache = getDefaultFileCacheBuilder(amperCachePath)
        }
        val templateContext = Context {
            defaultSettings()
        }
        val runtimeContext = Context {
            defaultSettings()
            scope = ResolutionScope.RUNTIME
        }
        val compileContext = Context {
            defaultSettings()
            scope = ResolutionScope.COMPILE
        }
        val root = RootDependencyNodeWithContext(
            templateContext = templateContext,
            children = coordinatesBag.flatMap { c ->
                listOf(
                    c.toMavenNode(runtimeContext),
                    c.toMavenNode(compileContext),
                )
            },
        )

        val resolver = Resolver()
        resolver.resolveDependencies(
            root = root,
            resolutionLevel = ResolutionLevel.NETWORK,
            transitive = true,
            incrementalCacheUsage = IncrementalCacheUsage.SKIP,
            unspecifiedVersionResolver = MavenDependencyUnspecifiedVersionResolverBase(),
        )
        resolver.downloadDependencies(
            node = root,
            downloadSources = false,
        )

        var errored = false

        val errors = root.resolutionErrors()
        if (errors.isNotEmpty()) {
            errored = true
            logger.error(buildString {
                appendLine("[root] resolution errors:")
                errors.forEach { appendLine("- ${it.detailedMessage}") }
            })
        }

        val resolved = mutableMapOf<String, UnresolvedNode>()
        val visited = mutableSetOf<MavenDependencyNode>()
        val queue = ArrayDeque<MavenDependencyNode>()
        queue.addAll(root.children.filterIsInstance<MavenDependencyNode>())
        while (queue.isNotEmpty()) {
            val node = queue.removeFirstOrNull()

            when {
                node == null -> {}
                visited.contains(node) -> {}
                else -> {
                    visited.add(node)
                    val errors = node.resolutionErrors()
                    when {
                        errors.isNotEmpty() -> {
                            errored = true
                            logger.error(buildString {
                                appendLine("[${node.idForBazel}] resolution errors:")
                                errors.forEach { appendLine("- ${it.detailedMessage}") }
                            })
                        }

                        else -> {
                            logger.info("[${node.idForBazel}] Processing node")
                            val actualNode = node.actualMavenDependencyOfVariantsMatching { it.klib() }
                            logger.info("[${node.idForBazel}] resolved to ${actualNode.idForBazel}")
                            when {
                                actualNode.resolutionErrors().isNotEmpty() -> {
                                    errored = true
                                    logger.error(buildString {
                                        appendLine("[${actualNode.idForBazel}] resolution errors:")
                                        actualNode.resolutionErrors().forEach { appendLine("- ${it.detailedMessage}") }
                                    })
                                }
                                else -> {
                                    val children = actualNode.children.filterIsInstance<MavenDependencyNode>()
                                    queue.addAll(children)

                                    val klibs = actualNode.filesMatching(logger) { it.klib() }
                                    logger.info("[${actualNode.idForBazel}] found klibs: $klibs")
                                    val klib = klibs.singleOrNull()
                                        ?: error("Expected exactly one klib for dependency ${actualNode.idForBazel}, got: $klibs")

                                    val sourceJars = actualNode.filesMatching(logger) { it.sourceJar() }
                                    logger.info("[${actualNode.idForBazel}] found sourceJars: $sourceJars")
                                    require(sourceJars.size <= 1) { "Expected at most one source jar, found ${sourceJars.size}: $sourceJars" }
                                    val sourceJar = sourceJars.singleOrNull()

                                    val (runtimeDeps, compileDeps) = children.partition {
                                        it.dependency.resolutionConfig.scope == ResolutionScope.RUNTIME
                                    }

                                    val initial by lazy {
                                        UnresolvedNode(
                                            id = node.idForBazel,
                                            variantId = actualNode.idForBazel,
                                            klib = klib,
                                            sourceJar = sourceJar,
                                            dependencies = runtimeDeps.asBazelIds(),
                                            exportedDependencies = compileDeps.asBazelIds(),
                                        )
                                    }
                                    val existing = resolved[actualNode.idForBazel]
                                    val updated = existing?.let {
                                        val exportedDeps = (existing.exportedDependencies + compileDeps.asBazelIds()).toSet()
                                        val deps =
                                            (existing.dependencies + runtimeDeps.asBazelIds()).toSet().minus(exportedDeps)
                                        it.copy(
                                            dependencies = deps.sorted(),
                                            exportedDependencies = exportedDeps.sorted(),
                                        )
                                    }

                                    resolved[actualNode.idForBazel] = updated ?: initial
                                }
                            }
                        }
                    }
                }
            }
        }

        require(!errored) {
            "failed to resolve coordinates, check error logs above."
        }

        return resolved
    }
}

private fun List<MavenDependencyNode>.asBazelIds() = map { it.idForBazel }.sorted().distinct()

private val MavenDependencyNode.idForBazel get() = "$group:$module:${resolvedVersion().orUnspecified()}"

private fun DependencyNode.resolutionErrors(): List<Message> = messages.filter { it.severity >= Severity.ERROR }

private fun Map<String, String>.sourceJar(): Boolean {
    return this["org.gradle.category"] == "documentation" && this["org.gradle.docstype"] == "sources" && this["org.jetbrains.kotlin.platform.type"] == "wasm" && this["org.jetbrains.kotlin.wasm.target"] == "js"
}

private fun Map<String, String>.klib(): Boolean {
    return this["org.gradle.category"] == "library" && this["org.gradle.usage"] in setOf(
        "kotlin-api", "kotlin-runtime"
    ) && this["org.jetbrains.kotlin.platform.type"] == "wasm" && this["org.jetbrains.kotlin.wasm.target"] == "js"
}

@Suppress("INVISIBLE_REFERENCE")
private suspend fun MavenDependencyNode.actualMavenDependencyOfVariantsMatching(attributeMatcher: (Map<String, String>) -> Boolean): MavenDependencyNode {
    val originalDep = this.dependency as MavenDependencyImpl
    require(originalDep.variants.isNotEmpty()) {
        error("no variants found for dependency $idForBazel")
    }
    val availableAts = originalDep.variants.filter { attributeMatcher(it.attributes) }.map { it.`available-at` }.toSet()
    return when {
        availableAts.isEmpty() -> error("no variants matched the attribute matcher")
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
    attributeMatcher: (Map<String, String>) -> Boolean
): List<UnresolvedMultiplatformLibraryArtifact> {
    logger.info("${idForBazel}: considering variants")
    val dep = this.dependency as MavenDependencyImpl
    require(dep.variants.isNotEmpty()) {
        error("no variants found for dependency $idForBazel")
    }
    return dep.variants.filter { variant ->
        logger.info("${idForBazel}: considering variant with attributes -> ${variant.attributes}")
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
