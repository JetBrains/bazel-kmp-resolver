"""The `kmp` module extension of rules_kotlin_external, consolidating intellij-community's vendored
`fleet/build/rules/kmp.bzl` into first-class Bazel rules.

    kmp = use_extension("@rules_kotlin_external//rules:extensions.bzl", "kmp")
    kmp.install(
        name = "kmp_deps",
        artifacts = ["org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"],
        platforms = ["jvm", "wasmjs"],
        repositories = ["https://repo1.maven.org/maven2"],
    )
    kmp.resolver(version = "0.5.0", sha256 = {"macos-arm64": "...", ...})
    use_repo(kmp, "kmp_deps_jvm", "kmp_deps_wasmjs")

Resolutions are cached through module extension facts keyed by the inputs and the resolver binary's
sha256: the resolver runs again only when either changes. The import rules instantiated in the hub
repositories are pluggable through the `*_import_bzl` labels of the install tag.
"""

load(":facts.bzl", "EMPTY_RESOLUTION_JSON", "resolution_fact_key")
load(":manifest.bzl", "PLATFORMS", "decode")
load(":materialize.bzl", "deps_repository_name", "kmp_deps_repository", "materialize_resolution", "render_build_file")
load(":naming.bzl", "maven_coordinate_parts", "maven_module_id", "validate_maven_module_id")
load(":repositories.bzl", "register_artifact_repositories")
load(":resolver.bzl", "node_tooling", "node_version", "resolve_fresh", "resolver_binary")

_DEFAULT_INSTALL_NAME = "kmp_deps"

def _read_tags(module_ctx):
    """The validated install tags of the root module, and its resolver tag, if any."""
    installs = []
    non_root_install_modules = []
    resolver_tags = []
    non_root_resolver_modules = []
    for module in module_ctx.modules:
        for tag in module.tags.install:
            if module.is_root:
                installs.append(tag)
            else:
                non_root_install_modules.append(module.name)
        for tag in module.tags.resolver:
            if module.is_root:
                resolver_tags.append(tag)
            else:
                non_root_resolver_modules.append(module.name)

    if non_root_install_modules:
        fail("The kmp.install(...) tags of module(s) %s are not supported: dependencies are declared by the root module only." % ", ".join(non_root_install_modules))
    if non_root_resolver_modules:
        fail("The kmp.resolver(...) tag of module(s) %s is not supported: the resolver is pinned by the root module only." % ", ".join(non_root_resolver_modules))
    if len(resolver_tags) > 1:
        fail("Only one kmp.resolver(...) tag is supported in the root module.")

    validated = [_validated_install(module_ctx, tag, index) for index, tag in enumerate(installs)]
    used_names = {}
    for install in validated:
        if install.name in used_names:
            fail("Two kmp.install(...) tags share the name '%s'." % install.name)
        used_names[install.name] = True
    return validated, (resolver_tags[0] if resolver_tags else None)

def _validated_install(module_ctx, tag, index):
    """An install tag with its declarations checked, or a failure naming the tag."""
    what = "kmp.install(...) tag #%s" % index

    name = tag.name if tag.name else _DEFAULT_INSTALL_NAME
    if not name or not all([char.isalnum() or char in "_.+-" for char in name.elems()]):
        fail("%s has name %r usable only with characters, digits, `_`, `.`, `+`, and `-`." % (what, name))

    platforms = tag.platforms
    if not platforms:
        fail("%s lists no platform; pick among %s." % (what, PLATFORMS))
    seen_platforms = {}
    for platform in platforms:
        if platform not in PLATFORMS:
            fail("%s got unsupported platform '%s', supported platforms: %s" % (what, platform, PLATFORMS))
        if platform in seen_platforms:
            fail("%s lists platform '%s' twice." % (what, platform))
        seen_platforms[platform] = True

    for coordinate in tag.artifacts:
        _validate_coordinate(what, coordinate)

    if tag.artifacts and not tag.repositories:
        fail("%s resolves artifacts but declares no repository; the resolver needs at least one Maven repository URL." % what)

    for source_module_id in sorted(tag.substitutions.keys()):
        validate_maven_module_id(source_module_id)
        target_coordinate = tag.substitutions[source_module_id]
        maven_coordinate_parts(target_coordinate)
        if maven_module_id(target_coordinate) == source_module_id:
            fail("%s substitutes %s by another version of itself (%s): versions are picked by the resolver's conflict resolution, declare the wanted version in artifacts instead." % (
                what,
                source_module_id,
                target_coordinate,
            ))

    return struct(
        name = name,
        artifacts = tag.artifacts,
        platforms = platforms,
        repositories = tag.repositories,
        substitutions = tag.substitutions,
        npm_package_version_overrides = tag.npm_package_version_overrides,
        npm_registry = tag.npm_registry,
        fetch_sources = tag.fetch_sources,
        import_rules = struct(
            jvm = tag.jvm_import_bzl,
            android = tag.android_import_bzl,
            klib = tag.klib_import_bzl,
            wasmjs = tag.wasmjs_import_bzl,
        ),
        is_dev = module_ctx.is_dev_dependency(tag),
    )

def _validate_coordinate(what, coordinate):
    stripped = coordinate[len("bom:"):] if coordinate.startswith("bom:") else coordinate
    maven_coordinate_parts(stripped)

def _watched_import_rules(module_ctx, installs):
    """The import rule labels, one struct per install, their files watched so that editing them
    re-renders the hub repositories (facts are hit, the resolver does not re-run)."""
    watched = {}
    for install in installs:
        for kind in ["jvm", "android", "klib", "wasmjs"]:
            label = getattr(install.import_rules, kind)
            if str(label) not in watched:
                watched[str(label)] = module_ctx.path(label)
    return watched

def _kmp_extension_impl(module_ctx):
    installs, resolver_tag = _read_tags(module_ctx)
    _watched_import_rules(module_ctx, installs)

    # Without any kmp.install(...) tag, every platform of the default install still gets an empty
    # repository so that no `use_repo` dangles.
    if not installs:
        installs = [struct(
            name = _DEFAULT_INSTALL_NAME,
            artifacts = [],
            platforms = PLATFORMS,
            repositories = [],
            substitutions = {},
            npm_package_version_overrides = {},
            npm_registry = "",
            fetch_sources = True,
            import_rules = struct(
                jvm = Label("//rules/imports:jvm.bzl"),
                android = Label("//rules/imports:android.bzl"),
                klib = Label("//rules/imports:klib.bzl"),
                wasmjs = Label("//rules/imports:wasmjs.bzl"),
            ),
            is_dev = not module_ctx.root_module_has_non_dev_dependency,
        )]

    installs_with_artifacts = [install for install in installs if install.artifacts]
    binary = None
    node = None
    node_ver = None
    if installs_with_artifacts:
        if resolver_tag == None:
            fail("No kmp.resolver(...) tag found: pin the resolver with kmp.resolver(version = ..., sha256 = {...}) or a local_<host> binary label.")
        binary = resolver_binary(module_ctx, resolver_tag)
        if any(["wasmjs" in install.platforms for install in installs_with_artifacts]):
            node = node_tooling(module_ctx)
            node_ver = node_version(module_ctx, node.node)

    facts = {}
    direct_repository_names = []
    dev_repository_names = []
    for install in installs:
        if not install.artifacts:
            resolution_json = EMPTY_RESOLUTION_JSON
        else:
            # The hermetic Node.js resolving the klibs' NPM closure is part of the key of `wasmjs`
            # resolutions: a change of npm may change the resolution.
            wants_node = "wasmjs" in install.platforms
            fact_key = resolution_fact_key(
                install,
                binary.sha256,
                node_ver if wants_node else None,
            )
            if fact_key in module_ctx.facts:
                resolution_json = module_ctx.facts[fact_key]
            else:
                resolution_json = resolve_fresh(module_ctx, install, binary, node if wants_node else None)
                facts[fact_key] = resolution_json

        resolution = decode(resolution_json)
        register_artifact_repositories(install.name, resolution)

        install_repository_names = []
        for platform in install.platforms:
            repository_name = deps_repository_name(install.name, platform)
            install_repository_names.append(repository_name)
            kmp_deps_repository(
                name = repository_name,
                build_file_content = render_build_file(
                    materialize_resolution(install.name, resolution, platform, install.fetch_sources),
                    platform,
                    install.import_rules,
                ),
            )
        if install.is_dev:
            dev_repository_names.extend(install_repository_names)
        else:
            direct_repository_names.extend(install_repository_names)

    return module_ctx.extension_metadata(
        root_module_direct_deps = direct_repository_names,
        root_module_direct_dev_deps = dev_repository_names,
        facts = facts,
    )

kmp = module_extension(
    implementation = _kmp_extension_impl,
    tag_classes = {
        "install": tag_class(attrs = {
            "name": attr.string(
                doc = """Name of the install, prefixing its generated repositories: the per-platform hubs
                `<name>_<platform>` (e.g. `kmp_deps_jvm`) and the artifact repositories behind them. Several
                `kmp.install(...)` tags, each with its own name, resolve dependency sets independently.""",
            ),
            "artifacts": attr.string_list(
                mandatory = True,
                doc = """Maven coordinates to resolve, `group:artifact:version`, optionally prefixed `bom:` to
                import a bill of materials. An empty list generates empty repositories: no resolution is
                run and no fact is recorded.""",
            ),
            "platforms": attr.string_list(
                mandatory = True,
                doc = """Platforms to resolve the artifacts for: `jvm`, `android`, `wasmjs`, or a
                Kotlin/Native target (`ios_arm64`, `macos_arm64`, ...). One `<name>_<platform>` repository
                is generated per platform, with the same target names in each.""",
            ),
            "repositories": attr.string_list(
                mandatory = True,
                doc = "Maven repository URLs forwarded to the resolver, in lookup order.",
            ),
            "substitutions": attr.string_dict(
                doc = """Maven module substitutions, keyed by group:artifact and valued group:artifact:version:
                the substitute is resolved in place of the substituted library, with its own artifacts and
                dependencies. Substituting a KMP library covers its platform-specific variants (`*-jvm`,
                `*-wasm-js`), which need no key of their own. A library cannot be substituted by another
                version of itself.""",
            ),
            "npm_package_version_overrides": attr.string_dict(
                doc = """Overrides of NPM package versions, keyed by package name, relevant to the `wasmjs`
                platform. Escape hatch to resolve manually the version conflicts between the NPM dependencies
                declared by different klibs.""",
            ),
            "npm_registry": attr.string(
                doc = "NPM registry URL for the `wasmjs` platform; the npmjs.org registry by default.",
            ),
            "fetch_sources": attr.bool(
                default = True,
                doc = "Whether the libraries' source jars are fetched and wired into their import targets.",
            ),
            "jvm_import_bzl": attr.label(
                default = Label("//rules/imports:jvm.bzl"),
                doc = """bzl file exporting the rules instantiated for JVM and jar-fallback Android targets
                under the names `jvm_import` and `jvm_library`. Swap them by passing your own label.""",
            ),
            "android_import_bzl": attr.label(
                default = Label("//rules/imports:android.bzl"),
                doc = """bzl file exporting the rules instantiated for `.aar` targets and Android redirects
                under the names `aar_import` and `android_library`.""",
            ),
            "klib_import_bzl": attr.label(
                default = Label("//rules/imports:klib.bzl"),
                doc = """bzl file exporting the rules instantiated for Kotlin/Native targets under the names
                `klib_import` and `klib_library`.""",
            ),
            "wasmjs_import_bzl": attr.label(
                default = Label("//rules/imports:wasmjs.bzl"),
                doc = """bzl file exporting the rules instantiated for the `wasmjs` platform under the names
                `wasmjs_import` and `wasmjs_library`.""",
            ),
        }),
        "resolver": tag_class(attrs = {
            "version": attr.string(
                doc = """Released version of the bazel-kmp-resolver to run, e.g. "0.5.0"; its release assets
                are `bazel-kmp-resolver-<os>-<arch>` binaries.""",
            ),
            "sha256": attr.string_dict(
                doc = """sha256 checksums of the resolver release assets, keyed by `linux-x64`,
                `linux-arm64`, `macos-x64`, `macos-arm64`, `windows-x64`, and optionally `windows-arm64`
                (which reuses the x64 asset). Required with [version]: the pin makes upgrades reviewable and
                is part of the resolution facts key.""",
            ),
            "local_linux_arm64": attr.label(
                doc = "Development: label of a locally built resolver binary for this host.",
            ),
            "local_linux_x64": attr.label(
                doc = "Development: label of a locally built resolver binary for this host.",
            ),
            "local_macos_arm64": attr.label(
                doc = "Development: label of a locally built resolver binary for this host.",
            ),
            "local_macos_x64": attr.label(
                doc = "Development: label of a locally built resolver binary for this host.",
            ),
            "local_windows_x64": attr.label(
                doc = "Development: label of a locally built resolver binary for this host.",
            ),
        }),
    },
    facts_version = 1,
    doc = """
      Kotlin Multiplatform dependency extension, the `rules_jvm_external` `maven.install` counterpart
      for KMP dependencies: resolves [artifacts] against [repositories] per [platform].

      It generates one `<name>_<platform>` repository per platform with one import target per library
      (wrapping its `http_file`-backed artifacts), versionless aliases per library and per variant,
      `klib_import` targets per C-interop klib, and `wasmjs_import` `npm_packages` backed by
      `http_archive` rules. Libraries without a platform artifact redirect to their dependencies.

      Resolutions are cached in MODULE.bazel.lock through module extension facts, keyed by the
      install's declarations, the resolver binary's sha256, and — for `wasmjs` — the hermetic Node.js
      version; deleting the lock file re-resolves everything.

      Private repositories authenticate through `NETRC` or a Bazel credential helper, configured
      through `RULES_KOTLIN_EXTERNAL_CREDENTIAL_HELPER` (or `KMP_RESOLVER_CREDENTIAL_HELPER`):
      `[scope=]path` entries separated by `;`, `%workspace%` standing for the workspace root, e.g.
      `common --repo_env=RULES_KOTLIN_EXTERNAL_CREDENTIAL_HELPER=%workspace%/tools/credential-helper`.
      The `..._TIMEOUT` and `..._CACHE_DURATION` counterparts follow Bazel's duration syntax.
    """,
)
