"""Materialization of a resolution into per-platform hub repositories, ported from
intellij-community's `fleet/build/rules/kmp.bzl` and extended to every platform of the resolver.

One `<install>_<platform>` repository per platform, with the same target names across platforms.
Each library becomes one target named after its platform-specific variant
(`io_ktor_ktor_client_core_jvm_3_6_0`) plus versionless aliases per umbrella and variant module
(`io_ktor_ktor_client_core`, `io_ktor_ktor_client_core_jvm`), so that consumers naming Maven modules
need no knowledge of the KMP umbrella or the resolved version. The instantiated rules are the
pluggable import rules of the `install` tag.
"""

load(":manifest.bzl", "aar", "class_path_jar", "cinterop_klibs", "klib", "libraries", "npm_packages", "platform_variant", "source_jar")
load(":naming.bzl", "build_target_name", "maven_module_id", "versionless_target_name")
load(":repositories.bzl", "artifact_label", "npm_label")

def deps_repository_name(install_name, platform):
    """The hub repository of an install's platform, e.g. `kmp_deps_jvm`."""
    return "%s_%s" % (install_name, platform)

def _kmp_deps_repository_impl(repository_ctx):
    repository_ctx.file("BUILD.bazel", repository_ctx.attr.build_file_content)

kmp_deps_repository = repository_rule(
    implementation = _kmp_deps_repository_impl,
    attrs = {
        "build_file_content": attr.string(
            mandatory = True,
            doc = "Generated BUILD file content.",
        ),
    },
)

def materialize_resolution(install_name, resolution, platform, fetch_sources):
    """The targets and aliases of one install's platform, ready to render into a BUILD file."""
    resolution_libraries = libraries(resolution)
    target_names = _library_target_names(resolution_libraries, platform)
    target_names_by_module = _target_names_by_module(target_names)

    targets = []
    materialized_target_names = {}
    for library_id in sorted(resolution_libraries.keys()):
        library = resolution_libraries[library_id]
        variant = platform_variant(library, platform)
        if variant == None:
            continue

        # Several libraries may resolve to the same platform-specific module (e.g. through
        # substitutions): the target is materialized once.
        target_name = build_target_name(variant["variantId"])
        if target_name in materialized_target_names:
            continue
        materialized_target_names[target_name] = True

        targets.append(_materialized_target(
            install_name,
            library_id,
            variant,
            platform,
            fetch_sources,
            target_names,
            target_names_by_module,
        ))

    return struct(
        aliases = _library_aliases(targets),
        targets = targets,
    )

def _materialized_target(install_name, library_id, variant, platform, fetch_sources, target_names, target_names_by_module):
    variant_source_jar = source_jar(variant)
    target = {
        "coordinate": library_id,
        "variant_id": variant["variantId"],
        "name": build_target_name(variant["variantId"]),
        "source_jar": artifact_label(install_name, variant_source_jar) if fetch_sources and variant_source_jar != None else None,
        "deps": _dependency_labels(library_id, variant, "dependencies", target_names, target_names_by_module),
        "exported_deps": _dependency_labels(library_id, variant, "exportedDependencies", target_names, target_names_by_module),
    }

    if platform == "jvm":
        jar = class_path_jar(variant)
        target["kind"] = "jvm" if jar != None else "jvm_group"
        target["jar"] = artifact_label(install_name, jar) if jar != None else None
    elif platform == "android":
        # An Android library publishes an `.aar`, or only a jar when it has no resources to package
        # (e.g. androidx.annotation); without either it redirects to its dependencies.
        android_aar = aar(variant)
        jar = class_path_jar(variant)
        if android_aar != None:
            target["kind"] = "aar"
            target["aar"] = artifact_label(install_name, android_aar)
        elif jar != None:
            target["kind"] = "jvm"
            target["jar"] = artifact_label(install_name, jar)
        else:
            target["kind"] = "android_group"
    elif platform == "wasmjs":
        target["kind"] = "wasmjs" if klib(variant) != None else "wasmjs_group"
        variant_klib = klib(variant)
        target["klib"] = artifact_label(install_name, variant_klib) if variant_klib != None else None
        target["npm_packages"] = {
            package["name"]: npm_label(install_name, package)
            for package in npm_packages(variant)
        }
    else:
        # A konan target (`ios_arm64`, `macos_arm64`, ...): the variant's klibs, its C-interop klibs
        # each as their own target, or only dependencies when it redirects.
        variant_klib = klib(variant)
        target["kind"] = "klib" if variant_klib != None else "klib_group"
        target["klib"] = artifact_label(install_name, variant_klib) if variant_klib != None else None
        target["konan_target"] = platform
        target["cinterop_klibs"] = [
            artifact_label(install_name, cinterop)
            for cinterop in cinterop_klibs(variant)
        ]
    return target

def _library_target_names(resolution_libraries, platform):
    """Maps every library coordinate and variant coordinate of the platform to its versionless
    target name, for the resolution of `group:artifact` dependency edges."""
    target_names = {}
    used_names = {}
    for library_id in sorted(resolution_libraries.keys()):
        library = resolution_libraries[library_id]
        variant = platform_variant(library, platform)
        if variant == None:
            # A library may resolve to different versions on different platforms: only the libraries
            # of the platform at hand compete for a versionless target name.
            continue
        versionless_name = versionless_target_name(library_id)
        used_library = used_names.get(versionless_name)
        if used_library != None and used_library != library_id:
            fail("Resolver produced multiple libraries for versionless KMP target '%s': %s and %s" % (
                versionless_name,
                used_library,
                library_id,
            ))
        used_names[versionless_name] = library_id
        target_names[library_id] = versionless_name
        target_names[variant["variantId"]] = versionless_name
    return target_names

def _target_names_by_module(target_names):
    target_names_by_module = {}
    for coordinate in sorted(target_names.keys()):
        target_name = target_names[coordinate]
        module_id = maven_module_id(coordinate)
        existing = target_names_by_module.get(module_id)
        if existing != None and existing != target_name:
            fail("Multiple KMP targets found for Maven module '%s': %s and %s" % (
                module_id,
                existing,
                target_name,
            ))
        target_names_by_module[module_id] = target_name
    return target_names_by_module

def _library_aliases(targets):
    """Versionless aliases per library umbrella module and per variant module. An ambiguous alias is
    dropped rather than guessed; one colliding with a real target name fails the resolution."""
    real_names = {}
    for target in targets:
        real_names[target["name"]] = True
        if target["kind"] == "jvm":
            real_names[_jvm_import_name(target["name"])] = True

    aliases = {}
    for target in targets:
        # Aliased by its own versionless name and its variant's (`io_ktor_ktor_client_core`,
        # `io_ktor_ktor_client_core_jvm`): consumers naming the Maven module need no knowledge of
        # the KMP umbrella.
        for alias_name in [versionless_target_name(target["coordinate"]), versionless_target_name(target["variant_id"])]:
            if alias_name == target["name"]:
                continue
            if alias_name in real_names:
                fail("Alias target name for %s collides with a real generated target: %s" % (
                    target["coordinate"],
                    alias_name,
                ))
            if alias_name in aliases:
                existing = aliases[alias_name]
                if existing != target["name"]:
                    aliases[alias_name] = None
                continue
            aliases[alias_name] = target["name"]

    return [
        {
            "actual": ":%s" % aliases[name],
            "name": name,
        }
        for name in sorted(aliases.keys())
        if aliases[name] != None
    ]

def _dependency_labels(library_id, variant, field, target_names, target_names_by_module):
    """The `:<target>` labels of a variant's dependency field, resolved through the versionless names."""
    dependencies = variant.get(field, [])
    labels = {}
    for dependency_id in dependencies:
        dependency_module_id = maven_module_id(dependency_id)
        target_name = target_names.get(dependency_id)
        if target_name == None:
            target_name = target_names_by_module.get(dependency_module_id)
        if target_name == None:
            fail("Library %s references unknown %s dependency: %s" % (
                library_id,
                field,
                dependency_id,
            ))
        label = ":%s" % target_name
        if label in labels:
            continue
        labels[label] = True
    return sorted(labels.keys())

def render_build_file(materialized, platform, import_rules):
    """The BUILD file of a hub repository: loads, package, targets, aliases.

    [import_rules] maps each import kind to the label of the bzl file exporting its rule symbols
    (`jvm_import`/`jvm_library`, `aar_import`/`android_library`, `klib_import`/`klib_library`,
    `wasmjs_import`/`wasmjs_library`). Canonical labels resolve wherever the repository is loaded
    from, whatever the consumer's repo mapping.
    """
    return "\n".join([
        _render_load_statements(platform, import_rules),
        "",
        "package(default_visibility = [\"//visibility:public\"])",
        "",
        _render_targets_block(materialized.targets, platform),
        "",
        _render_aliases_block(materialized.aliases),
        "",
    ])

def _render_load_statements(platform, import_rules):
    if platform == "jvm":
        return _render_load(import_rules.jvm, "jvm_import", "jvm_library")
    if platform == "android":
        # An Android repository also materializes JVM imports: a library publishing no `.aar` falls
        # back to its jar variant.
        return "\n".join([
            _render_load(import_rules.jvm, "jvm_import", "jvm_library"),
            _render_load(import_rules.android, "aar_import", "android_library"),
        ])
    if platform == "wasmjs":
        return _render_load(import_rules.wasmjs, "wasmjs_import", "wasmjs_library")
    return _render_load(import_rules.klib, "klib_import", "klib_library")

def _render_load(label, *symbols):
    return "load(%s%s)" % (
        _quote(str(label)),
        "".join([", %s" % _quote(symbol) for symbol in symbols]),
    )

def _render_targets_block(targets, platform):
    blocks = []
    for target in targets:
        lines = [
            "# %s" % target["coordinate"],
        ]
        lines.extend(_render_target(target))
        blocks.append("\n".join(lines))
    return "\n\n".join(blocks)

def _render_target(target):
    kind = target["kind"]
    if kind == "jvm":
        return _render_jvm_target(target)
    if kind == "jvm_group":
        return _render_jvm_group_target(target)
    if kind == "aar":
        return _render_aar_import(target)
    if kind == "android_group":
        return _render_android_group_target(target)
    if kind == "klib":
        return _render_klib_import(target)
    if kind == "klib_group":
        return _render_klib_group_target(target)
    if kind == "wasmjs":
        return _render_wasmjs_import(target)
    if kind == "wasmjs_group":
        return _render_wasmjs_group_target(target)
    fail("Unknown materialized target kind: %s" % kind)

def _render_source_jar_attr(target):
    if target["source_jar"] == None:
        return []
    return ["    source_jar = %s," % _quote(target["source_jar"])]

def _render_jvm_target(target):
    """The `jvm_import` of a library's jar and the `jvm_library` wrapper; `jvm_import` has no
    `exports`, hence the wrapper. Aliases and dependency labels point at the wrapper."""
    import_name = _jvm_import_name(target["name"])
    lines = [
        "jvm_import(",
        "    name = %s," % _quote(import_name),
        "    jar = %s," % _quote(target["jar"]),
    ]
    lines.extend(_render_source_jar_attr(target))
    lines.extend([
        ")",
        "",
        "jvm_library(",
        "    name = %s," % _quote(target["name"]),
    ])
    lines.extend(_render_label_list_attr("exports", [":%s" % import_name] + target["exported_deps"]))
    lines.extend(_render_label_list_attr("runtime_deps", target["deps"]))
    lines.append(")")
    return lines

def _render_jvm_group_target(target):
    """The `jvm_library` of a library carrying no jar for the platform, only dependencies: the
    library redirects to its dependencies (e.g. JetBrains Compose's Android variants → AndroidX)."""
    lines = [
        "jvm_library(",
        "    name = %s," % _quote(target["name"]),
    ]
    lines.extend(_render_label_list_attr("exports", target["exported_deps"]))
    lines.extend(_render_label_list_attr("runtime_deps", target["deps"]))
    lines.append(")")
    return lines

def _render_aar_import(target):
    lines = [
        "aar_import(",
        "    name = %s," % _quote(target["name"]),
        "    aar = %s," % _quote(target["aar"]),
    ]
    lines.extend(_render_source_jar_attr(target))
    lines.extend(_render_label_list_attr("deps", target["deps"]))
    lines.extend(_render_label_list_attr("exported_deps", target["exported_deps"]))
    lines.append(")")
    return lines

def _render_android_group_target(target):
    lines = [
        "android_library(",
        "    name = %s," % _quote(target["name"]),
    ]
    lines.extend(_render_label_list_attr("deps", target["deps"]))
    lines.extend(_render_label_list_attr("exported_deps", target["exported_deps"]))
    lines.append(")")
    return lines

def _render_klib_import(target):
    """The `klib_import` of a library's klib for the konan target, and one per C-interop klib; the
    C-interop klibs get their own targets instead of being wired into the main target's deps."""
    lines = [
        "klib_import(",
        "    name = %s," % _quote(target["name"]),
        "    klib = %s," % _quote(target["klib"]),
        "    konan_target = %s," % _quote(target["konan_target"]),
    ]
    lines.extend(_render_source_jar_attr(target))
    lines.extend(_render_label_list_attr("deps", target["deps"]))
    lines.extend(_render_label_list_attr("exported_deps", target["exported_deps"]))
    lines.append(")")

    for index, cinterop_klib in enumerate(target["cinterop_klibs"]):
        lines.extend([
            "",
            "klib_import(",
            "    name = %s," % _quote("%s_cinterop_%s" % (target["name"], index)),
            "    klib = %s," % _quote(cinterop_klib),
            "    konan_target = %s," % _quote(target["konan_target"]),
            ")",
        ])
    return lines

def _render_klib_group_target(target):
    lines = [
        "klib_library(",
        "    name = %s," % _quote(target["name"]),
    ]
    lines.extend(_render_label_list_attr("deps", target["deps"]))
    lines.extend(_render_label_list_attr("exported_deps", target["exported_deps"]))
    lines.append(")")
    return lines

def _render_wasmjs_import(target):
    """The `wasmjs_import` of a library's klib, carrying its embedded `package.json`'s NPM packages."""
    lines = [
        "wasmjs_import(",
        "    name = %s," % _quote(target["name"]),
        "    klib = %s," % _quote(target["klib"]),
    ]
    lines.extend(_render_source_jar_attr(target))
    lines.extend(_render_label_list_attr("deps", target["deps"]))
    lines.extend(_render_label_list_attr("exported_deps", target["exported_deps"]))
    lines.extend(_render_string_dict_attr("npm_packages", target["npm_packages"]))
    lines.append(")")
    return lines

def _render_wasmjs_group_target(target):
    lines = [
        "wasmjs_library(",
        "    name = %s," % _quote(target["name"]),
    ]
    lines.extend(_render_label_list_attr("deps", target["deps"]))
    lines.extend(_render_label_list_attr("exported_deps", target["exported_deps"]))
    lines.append(")")
    return lines

def _render_aliases_block(aliases):
    blocks = []
    for alias in aliases:
        blocks.append("\n".join([
            "alias(",
            "    name = %s," % _quote(alias["name"]),
            "    actual = %s," % _quote(alias["actual"]),
            ")",
        ]))
    return "\n\n".join(blocks)

def _jvm_import_name(target_name):
    return "%s_import" % target_name

def _render_string_dict_attr(name, values):
    if not values:
        return []
    lines = [
        "    %s = {" % name,
    ]
    for key in sorted(values.keys()):
        lines.append("        %s: %s," % (_quote(key), _quote(values[key])))
    lines.append("    },")
    return lines

def _render_label_list_attr(name, values):
    if not values:
        return []
    lines = [
        "    %s = [" % name,
    ]
    for value in values:
        lines.append("        %s," % _quote(value))
    lines.append("    ],")
    return lines

def _quote(value):
    return "\"%s\"" % value.replace("\\", "\\\\").replace("\"", "\\\"")
