package org.jetbrains.kmp.resolver

import java.net.http.HttpClient
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.absolutePathString
import kotlinx.coroutines.*
import kotlinx.serialization.*
import org.jetbrains.amper.dependency.resolution.*
import org.jetbrains.amper.dependency.resolution.attributes.KotlinPlatformType
import org.jetbrains.amper.dependency.resolution.diagnostics.Message
import org.jetbrains.amper.dependency.resolution.diagnostics.PlatformsAreNotSupported
import org.jetbrains.amper.dependency.resolution.diagnostics.Severity
import org.jetbrains.gradle.module.metadata.format.Variant
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Substitution ID represents a groupId:artifactId string */
internal typealias SubstitutionId = String

internal typealias Substitutions = Map<SubstitutionId, MultiplatformLibraryId>

/**
 * The KMP library (umbrella) a Maven module (`group:artifact`) is a platform-specific variant of,
 * for the modules of a platform's dependency graph that are such variants.
 */
internal typealias KmpParents = Map<SubstitutionId, MultiplatformLibraryId>

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

@Serializable
internal data class MultiplatformLibrary(
    val id: MultiplatformLibraryId,
    val variants: List<MultiplatformVariant>,
)

internal class MultiplatformResolver(
    cachePath: Path,
    private val platforms: Set<ResolutionPlatform>,
    private val repositories: List<MavenRepository>,
    private val substitutions: Substitutions,
    private val httpClient: HttpClient,
    private val artifactResolver: ArtifactUrlResolver,
    private val npmResolver: NpmResolver?,
) {
    val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Amper caches files by their Maven path, whatever the repository they come from, while a
     * library could be published with different contents in different repositories: each ordered
     * list of repositories gets its own cache, so that a cache filled with other repositories is
     * never used for a resolution against these ones.
     */
    private val amperCachePath: Path =
        cachePath.resolve("amper-cache").resolve(repositories.cacheDirectoryName())

    /**
     * Requested platforms in the order of [supportedPlatforms], which is the order variants are
     * listed in the manifest.
     */
    private val orderedPlatforms: List<ResolutionPlatform> = supportedPlatforms.filter {
        it in platforms
    }

    init {
        require(platforms.isNotEmpty()) { "At least one platform must be requested" }
        substitutions.forEach { (substituted, substitute) ->
            require(substituted != substitute.toSubstitutionId()) {
                "Substitution of $substituted by ${substitute.gav} substitutes a library by another version of itself, " +
                    "which is not supported: versions are picked by conflict resolution among the requested ones"
            }
        }
        platforms.forEach { platform ->
            require(platform in supportedPlatforms) {
                "Platform ${platform.pretty} is not supported, supported platforms: ${supportedPlatforms.joinToString { it.pretty }}"
            }
        }
    }

    internal suspend fun resolve(coordinatesBag: Collection<String>): List<MultiplatformVariant> {
        logger.debug("Resolving dependency graph...")
        logger.info("Cache location: ${amperCachePath.absolutePathString()}")
        logger.info("Against repositories:\n${repositories.joinToString("\n") { "  ${it.url}" }}")
        if (substitutions.isNotEmpty()) {
            logger.info(
                "Using substitutions:\n${substitutions.entries.joinToString("\n") { "  ${it.key} -> ${it.value.gav}" }}",
            )
        }
        logger.info("Platforms: ${orderedPlatforms.joinToString { it.pretty }}")
        logger.info("Coordinates:\n${coordinatesBag.joinToString("\n") { "  $it" }}")
        // Amper exposes the platform-specific variants of KMP libraries (and their link to the
        // umbrella library) only
        // when resolving for a single platform, so each platform gets its own resolution.
        // Resolutions run sequentially because they share the same Amper cache directory, and
        // platforms are
        // iterated in a fixed order so that the manifest does not depend on the order of the
        // `--platform` flags.
        // Substitutes are resolved in place of the libraries they substitute: they take part in the
        // resolution as
        // roots, so that their artifacts, hashes and dependencies are the ones Amper resolves for
        // them, while the
        // substituted libraries are dropped from the result and the edges pointing at them
        // redirected (see [collectNodes]).
        val substitutes = substitutions.values.map { it.gav }.distinct()
        val nodes = orderedPlatforms.flatMap { platform ->
            logger.debug("Resolving dependency graph for platform ${platform.pretty}...")
            val root =
                callAmperResolution(
                    coordinatesBag = (coordinatesBag.toList() + substitutes).distinct(),
                    platforms = setOf(platform),
                    scope =
                        listOf(
                            ResolutionScope.RUNTIME,
                            ResolutionScope.COMPILE,
                        ),
                )
            logger.debug("Collecting nodes of the ${platform.pretty} dependency graph...")
            collectNodes(root, platform)
                .dropLibrariesOnlyReachableFromSubstitutes(coordinatesBag, substitutes)
        }
        logger.debug("Resolving NPM dependencies declared by the klibs of the dependency graph...")
        val npmPackagesByVariant = resolveNpmPackages(nodes)
        logger.debug(
            "Resolving artifacts of nodes of the dependency graph against the specified repositories...",
        )
        return resolveArtifacts(
                nodes,
                npmPackagesByVariant,
            )
            .also { variants -> variants.requireOneVariantPerPlatform() }
    }

    /**
     * A library has at most one variant per platform, otherwise consumers could not tell which
     * artifacts to use.
     */
    private fun List<MultiplatformVariant>.requireOneVariantPerPlatform() {
        val duplicates = groupBy { it.id to it.platform }.filterValues { it.size > 1 }
        require(duplicates.isEmpty()) {
            buildString {
                appendLine("Libraries resolved to several variants of the same platform:")
                duplicates.forEach { (key, variants) ->
                    appendLine(
                        "\t[${key.first.gav}] ${key.second}: ${variants.map { it.variantId.gav }}",
                    )
                }
            }
        }
    }

    private suspend fun resolveNpmPackages(
        nodes: List<UnresolvedMultiplatformLibrary>,
    ): Map<MultiplatformLibraryId, List<NpmMultiplatformLibraryArtifact>> {
        val requests =
            nodes
                /* Support resolving npm packages for wasm js */
                .filterIsInstance<UnresolvedMultiplatformLibrary.Klib>()
                .filter { node -> node.platform == ResolutionPlatform.WASM_JS }
                .flatMap { node ->
                    withContext(Dispatchers.IO) {
                        node.mainKlibPaths().mapNotNull { klib ->
                            val manifest = readEmbeddedNpmManifest(klib) ?: return@mapNotNull null
                            if (manifest.dependencies.isEmpty()) return@mapNotNull null
                            NpmResolutionRequest(
                                variantId = node.variantId,
                                manifest = manifest,
                            )
                        }
                    }
                }

        /*
        Guard:
        A npmResolver may only be configured when resolving relevant platforms (e.g. wasm_js).
        When not resolving any web platforms, we do not expect any npm dependencies/requests.
         */
        return when {
            requests.isEmpty() -> emptyMap()
            npmResolver == null -> {
                logger.warn("Cannot resolve NPM packages: Missing 'npmResolver' ")
                emptyMap()
            }

            else -> npmResolver.resolve(requests)
        }
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
        // Amper takes the HTTP client of its downloads from the resolution cache under its internal
        // `httpClientKey`, a
        // `Key<java.net.http.HttpClient>("httpClient")`; `Key` is a data class so this key is equal
        // to it. The cache is
        // shared by every context of the session, as Amper expects, so all downloads go through
        // [httpClient].
        val resolutionCache = Cache().apply { set(Key<HttpClient>("httpClient"), httpClient) }
        val templateContext =
            Context(resolutionCache) {
                defaultSettings()
            }
        val contexts = scope.map {
            Context(resolutionCache) {
                defaultSettings()
                this.scope = it
            }
        }
        val root =
            RootDependencyNodeWithContext(
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
        platform: ResolutionPlatform,
    ): List<UnresolvedMultiplatformLibrary> {
        val nodes =
            root
                .distinctBfsSequence { child, _ ->
                    when (child) {
                        is MavenDependencyNode -> child.isCollectedFor(platform)
                        else -> false
                    }
                }
                .filterIsInstance<MavenDependencyNode>()
                .toList()
        // Amper tells the KMP library a node is a platform-specific variant of per graph node, that
        // is per requester: a
        // variant also requested directly, as POM-only JVM libraries do with `*-jvm` artifacts, has
        // nodes whose parent
        // is that requester. The KMP parent is therefore looked up per Maven module, across all the
        // nodes of the graph.
        val kmpParents: KmpParents =
            nodes
                .mapNotNull { node ->
                    node.getParentKmpLibraryCoordinates()?.let { parent ->
                        node.gav.toSubstitutionId() to
                            MultiplatformLibraryId(
                                groupId = parent.groupId,
                                artifactId = parent.artifactId,
                                version =
                                    parent.version
                                        ?: error(
                                            "${node.gav.gav} parent $parent must have a version",
                                        ),
                            )
                    }
                }
                .toMap()
        val errorsByNode =
            nodes
                .map { node ->
                    node.gav to node.resolutionErrors(logger)
                }
                .filter { (_, errors) ->
                    errors.isNotEmpty()
                }
        require(errorsByNode.isEmpty()) {
            buildString {
                appendLine("Amper resolution failed with errors:")
                errorsByNode.forEach { (gav, errors) ->
                    appendLine("\t[$gav] errors:")
                    errors.forEach { error ->
                        appendLine("\t\t${error.message} (${error.details ?: ""})")
                    }
                }
            }
        }

        val kmpParentsSet = kmpParents.values.toSet()
        val (substitutedNodes, filteredNodes) =
            nodes
                .filter { node -> node.gav !in kmpParentsSet }
                .partition { node -> node.isSubstituted(kmpParents) }
        substitutedNodes.forEach { node ->
            logger.debug(
                "[${node.gav.gav}] dropped, it is substituted (platform ${platform.pretty})",
            )
        }

        // some KMP dependencies are incorrectly declaring a JVM dependency
        // e.g. `ai.jetbrains.code.files:code-files-model-wasm-js:1.0.0-beta.170` which depends on
        // `org.slf4j:slf4j-api:2.0.17`
        // a purely JVM dependency
        val possibleDependencies =
            filteredNodes
                .flatMap { node ->
                    listOfNotNull(
                        node.gav.toSubstitutionId(),
                        kmpParents[node.gav.toSubstitutionId()]?.toSubstitutionId(),
                    )
                }
                .toSet()
        return filteredNodes
            .groupBy { it.gav }
            .map { (_, scopedNodes) ->
                when {
                    platform == ResolutionPlatform.WASM_JS ||
                        platform.type == PlatformType.NATIVE ->
                        UnresolvedMultiplatformLibrary.Klib(
                            platform,
                            resolvedNodes = scopedNodes,
                            substitutions = substitutions,
                            kmpParents = kmpParents,
                            possibleDependencies = possibleDependencies,
                            repositories = repositories,
                        )

                    platform == ResolutionPlatform.JVM ->
                        UnresolvedMultiplatformLibrary.Jvm(
                            resolvedNodes = scopedNodes,
                            substitutions = substitutions,
                            kmpParents = kmpParents,
                            possibleDependencies = possibleDependencies,
                            repositories = repositories,
                        )

                    platform == ResolutionPlatform.ANDROID ->
                        UnresolvedMultiplatformLibrary.Android(
                            resolvedNodes = scopedNodes,
                            substitutions = substitutions,
                            kmpParents = kmpParents,
                            possibleDependencies = possibleDependencies,
                            repositories = repositories,
                        )

                    else -> error("Platform ${platform.pretty} is not supported")
                }
            }
    }

    /**
     * Whether this node, or the KMP library it is a platform-specific variant of, is substituted by
     * another library: its substitute is resolved in its place (see [resolve]), so the node itself
     * is not part of the result.
     */
    private fun MavenDependencyNode.isSubstituted(kmpParents: KmpParents): Boolean =
        substitutionKeys(kmpParents).isNotEmpty()

    /**
     * The substitution keys (`group:artifact`) this node matches, by its own coordinates or by the
     * KMP library it is a platform-specific variant of.
     */
    private fun MavenDependencyNode.substitutionKeys(kmpParents: KmpParents): List<SubstitutionId> =
        listOfNotNull(
                gav.toSubstitutionId(),
                kmpParents[gav.toSubstitutionId()]?.toSubstitutionId(),
            )
            .filter { it in substitutions }

    /**
     * Substitutes are resolved as roots whether or not something depends on them: those that
     * nothing reachable from the asked [coordinatesBag] depends on are dropped, along with what
     * only they pull in, so that the manifest only contains what the asked coordinates need.
     * Libraries reachable from neither (e.g. pulled by a pom-only intermediate, which the manifest
     * cannot express) are kept, as they were before substitutes became roots.
     */
    private fun List<UnresolvedMultiplatformLibrary>.dropLibrariesOnlyReachableFromSubstitutes(
        coordinatesBag: Collection<String>,
        substitutes: Collection<String>,
    ): List<UnresolvedMultiplatformLibrary> {
        val librariesByModule = associateBy { it.id.toSubstitutionId() }
        val reachableFromAsked = librariesByModule.reachableFrom(coordinatesBag)
        val onlyReachableFromSubstitutes =
            librariesByModule.reachableFrom(substitutes) - reachableFromAsked
        onlyReachableFromSubstitutes.forEach { library ->
            logger.debug(
                "[${library.id.gav}] dropped, nothing asked depends on it, it is only reachable from a substitute",
            )
        }
        return this - onlyReachableFromSubstitutes
    }

    /**
     * Libraries (keyed by module) reachable from the given root coordinates through their
     * dependencies, substitutions applied to the roots.
     */
    private fun Map<SubstitutionId, UnresolvedMultiplatformLibrary>.reachableFrom(
        roots: Collection<String>,
    ): Set<UnresolvedMultiplatformLibrary> {
        val rootModules =
            substitutions.substituteSubstitutionIds(
                roots.map { root ->
                    root.toMavenCoordinates().let { substitutionId(it.groupId, it.artifactId) }
                },
            )
        val reached = linkedSetOf<UnresolvedMultiplatformLibrary>()
        val pending = ArrayDeque(rootModules.mapNotNull { this[it] })
        while (pending.isNotEmpty()) {
            val library = pending.removeFirst()
            if (reached.add(library)) {
                (library.dependencies + library.exportedDependencies).mapNotNullTo(pending) {
                    this[it]
                }
            }
        }
        return reached
    }

    /**
     * Filters out dependencies that are not relevant for the given platform. This fixes a current
     * design issue of the dependency resolver:
     *
     * A request to resolve dependencies provides a flat list of dependencies, and a flat list of
     * platforms. This leads to dependencies, being published for non-kmp being requested for kmp
     * platforms.
     *
     * E.g. a regular pom based java dependency may end up being requested for klib based platforms.
     * E.g. a regular android aar based dependency may end up being requested for non-android
     * platforms.
     *
     * The publication metadata of such publications does not carry KMP information. The resolution
     * rules for compatibility will resolve the artifacts. E.g. a regular android aar based
     * dependency may still use `org.gradle.usage=java-api` which could be marked compatible with
     * JVM and is therefore resolved. Usually such a library is not present/expected in JVM based
     * dependency graphs. Any JVM based dependency graph containing such a library can be considered
     * rogue and the dependency resolution therefore erratic.
     *
     * As long as we use such a 'flat' resolution context for all dependencies, we need to filter
     * those nodes explicitly. (An alternative approach would be to resolve platform specific
     * dependencies **only** in their given resolution context.)
     */
    private fun MavenDependencyNode.isCollectedFor(
        platform: ResolutionPlatform,
    ): Boolean {
        if (isBom) {
            return false
        }

        /*
        Kotlin Toolchain was able to infer from the given publication attributes that the platform is just not supported.
        Shall later be replaced by a dedicated API which does not rely on such typed messages.
         */
        if (dependency.messages.any { it is PlatformsAreNotSupported }) {
            return false
        }

        /*
        dependency.packaging is only populated for pom based dependencies (no module files).
        This already indicates that the dependency is not a KMP library.
        https://maven.apache.org/pom.html#Packaging
         */

        /*
        Classpath JARs and POM aggregators are supported on Android and JVM.
         */
        if (
            dependency.packaging in listOf("jar", "bundle", "pom") &&
                platform !in listOf(ResolutionPlatform.JVM, ResolutionPlatform.ANDROID)
        ) {
            return false
        }

        /*
        Different JVM packaged POM only dependency, which requires JVM
         */
        if (
            dependency.packaging in listOf("war", "ear", "rar", "maven-plugin", "ejb") &&
                platform != ResolutionPlatform.JVM
        ) {
            return false
        }

        /*
        Plain old, pom based, android dependency requested in a non-android context
         */
        if (dependency.packaging == "aar" && platform != ResolutionPlatform.ANDROID) {
            return false
        }

        /*
        Gradle Module metadata based android dependencies resolved in a non-android context
         */
        val files = dependency.files(withSources = false)
        if (files.any { it.isAar() } && platform != ResolutionPlatform.ANDROID) {
            return false
        }

        /*
        Guard KMP libraries: We expect KMP only platforms to
        - be Gradle .module metadata based
        - have at least one resolved variant with a Kotlin platform type

        Note: This is using an internal KTC API that we agreed upon for 0.14.0
        This is planned to be handled either by KTC directly, by not resolving such variants
        or by providing a public API.
         */
        if (platform !in listOf(ResolutionPlatform.JVM, ResolutionPlatform.ANDROID)) {
            val variants =
                dependency.javaClass
                    .getMethod($$"getVariants$org_jetbrains_amper_dependency_resolution")
                    .invoke(dependency) as List<*>
            if (
                variants.none { variant ->
                    variant as Variant
                    variant.attributes[KotlinPlatformType.name] != null
                }
            ) {
                return false
            }
        }

        return true
    }

    private suspend fun resolveArtifacts(
        nodes: List<UnresolvedMultiplatformLibrary>,
        npmPackagesByVariant: Map<MultiplatformLibraryId, List<NpmMultiplatformLibraryArtifact>>,
    ): List<MultiplatformVariant> = coroutineScope {
        nodes
            .map { unresolvedNode ->
                when (unresolvedNode) {
                    is UnresolvedMultiplatformLibrary.Klib ->
                        async {
                            unresolvedNode.resolve(
                                artifactResolver,
                                npmPackagesByVariant[unresolvedNode.variantId].orEmpty(),
                            )
                        }

                    is UnresolvedMultiplatformLibrary.Jvm ->
                        async {
                            unresolvedNode.resolve(artifactResolver)
                        }

                    is UnresolvedMultiplatformLibrary.Android ->
                        async {
                            unresolvedNode.resolve(artifactResolver)
                        }
                }
            }
            .awaitAll()
    }
}

/** Name of the cache directory of these repositories, a digest of their URLs in order. */
private fun List<MavenRepository>.cacheDirectoryName(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(joinToString("\n") { it.url }.toByteArray())
        .toHexString()
        .take(16)

private fun DependencyNode.resolutionErrors(logger: Logger): List<Message> =
    messages
        .filter { it.severity >= Severity.ERROR }
        .mapNotNull {
            when (it) {
                // the library is not published for the platform being resolved, it is simply not
                // part of this platform's graph
                is PlatformsAreNotSupported -> {
                    logger.warn(
                        "[${it.coordinates}] skipping unsupported platform(s): ${it.requestedUnsupportedPlatforms.joinToString { p -> p.pretty }}",
                    )
                    null
                }

                else -> it
            }
        }

private suspend fun UnresolvedMultiplatformLibraryArtifact.resolve(
    artifactUrlResolver: ArtifactUrlResolver,
): MavenMultiplatformLibraryArtifact {
    val urls = coroutineScope {
        possibleLocations
            .map { location ->
                async { artifactUrlResolver.artifactExistsAt(location) }
            }
            .awaitAll()
            .filterIsInstance<ArtifactFile.Resolved>()
            .map { it.url }
    }
    require(urls.isNotEmpty()) {
        "[$groupId:$artifactId:$version] artifact could not be find in any of the specified repositories"
    }

    return MavenMultiplatformLibraryArtifact(
        integrity = integrity,
        groupId = groupId,
        artifactId = artifactId,
        version = version,
        urls = urls,
        type = type,
    )
}

private suspend fun UnresolvedMultiplatformLibrary.Klib.resolve(
    artifactUrlResolver: ArtifactUrlResolver,
    npmPackages: List<NpmMultiplatformLibraryArtifact>,
): MultiplatformVariant = coroutineScope {
    val resolvedKlibs = async {
        klibs().map { file ->
            file.resolve(artifactUrlResolver)
        }
    }
    val resolvedSourceJars = sourceJars().map { async { it.resolve(artifactUrlResolver) } }

    return@coroutineScope MultiplatformVariant(
        platform = platform,
        id = id,
        variantId = variantId,
        dependencies = dependencies.sorted(),
        exportedDependencies = exportedDependencies.sorted(),
        artifacts =
            buildList {
                addAll(resolvedKlibs.await())
                addAll(resolvedSourceJars.awaitAll())
                addAll(npmPackages)
            },
    )
}

private suspend fun UnresolvedMultiplatformLibrary.Jvm.resolve(
    artifactUrlResolver: ArtifactUrlResolver,
): MultiplatformVariant = coroutineScope {
    val resolvedJars = jars().map { async { it.resolve(artifactUrlResolver) } }
    val resolvedSourceJar = sourceJars().map { async { it.resolve(artifactUrlResolver) } }
    MultiplatformVariant(
        platform = platform,
        id = id,
        variantId = variantId,
        dependencies = dependencies.sorted(),
        exportedDependencies = exportedDependencies.sorted(),
        artifacts = resolvedJars.awaitAll() + resolvedSourceJar.awaitAll(),
    )
}

private suspend fun UnresolvedMultiplatformLibrary.Android.resolve(
    artifactUrlResolver: ArtifactUrlResolver,
): MultiplatformVariant = coroutineScope {
    val resolvedLibrary = jarOrAarArtifacts().map { async { it.resolve(artifactUrlResolver) } }
    val resolvedSourceJars = sourceJars().map { async { it.resolve(artifactUrlResolver) } }

    MultiplatformVariant(
        platform = platform,
        id = id,
        variantId = variantId,
        dependencies = dependencies.sorted(),
        exportedDependencies = exportedDependencies.sorted(),
        artifacts =
            buildList {
                addAll(resolvedLibrary.awaitAll())
                addAll(resolvedSourceJars.awaitAll())
            },
    )
}

private fun String.toMavenNode(context: Context): MavenDependencyNodeWithContext =
    context.toMavenDependencyNode(coordinates = toMavenCoordinates(), isBom = startsWith("bom:"))

private fun String.toMavenCoordinates(): MavenCoordinates {
    val parts = removePrefix("bom:").trim().split(":")
    return MavenCoordinates(
        groupId = parts[0],
        artifactId = parts[1],
        version =
            when {
                parts.size > 2 -> parts[2]
                else -> null
            },
    )
}

internal fun MultiplatformLibraryId.toSubstitutionId(): SubstitutionId =
    substitutionId(groupId, artifactId)

internal fun substitutionId(groupId: String, artifactId: String) = "$groupId:$artifactId"

internal fun Substitutions.substituteSubstitutionIds(
    originalIds: Collection<SubstitutionId>,
): Set<SubstitutionId> =
    originalIds
        .map { originalId ->
            when (val subId = this[originalId]) {
                null -> originalId
                else -> subId.toSubstitutionId()
            }
        }
        .toSet()
