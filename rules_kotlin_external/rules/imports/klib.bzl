"""Default Kotlin/Native import rules, the instantiation target of the konan-target hub repositories.

Deliberately minimal: `klib_import` exposes the `.klib` and its dependency tree as files and a
`KlibInfo` provider. Real compilation and linking plug in rules_kotlin's `kt_klib_import` through
the `klib_import_bzl` label of the `kmp.install(...)` tag.
"""

KlibInfo = provider(
    doc = "An imported `.klib` Kotlin/Native library and its dependency tree.",
    fields = {
        "klib": "The `.klib` file of the library, None for a redirect to its dependencies.",
        "source_jar": "The sources jar of the library, when fetched.",
        "konan_target": "The Kotlin/Native target the klib was compiled for, e.g. `ios_arm64`.",
        "deps": "The `KlibInfo`s of the library's link dependencies (depset).",
        "exported_deps": "The `KlibInfo`s of the dependencies compile-visible to dependents (depset).",
    },
)

def _transitive_files(deps):
    return depset(transitive = [dep[DefaultInfo].files for dep in deps])

def _transitive_klibinfos(deps, field):
    return depset(
        [dep[KlibInfo] for dep in deps if KlibInfo in dep],
        transitive = [getattr(dep[KlibInfo], field) for dep in deps if KlibInfo in dep],
    )

def _klib_import_impl(ctx):
    files = [ctx.file.klib]
    if ctx.file.source_jar != None:
        files.append(ctx.file.source_jar)
    return [
        DefaultInfo(
            files = depset(files, transitive = [_transitive_files(ctx.attr.deps + ctx.attr.exported_deps)]),
        ),
        KlibInfo(
            klib = ctx.file.klib,
            source_jar = ctx.file.source_jar,
            konan_target = ctx.attr.konan_target,
            deps = _transitive_klibinfos(ctx.attr.deps, "deps"),
            exported_deps = _transitive_klibinfos(ctx.attr.exported_deps, "exported_deps"),
        ),
    ]

klib_import = rule(
    implementation = _klib_import_impl,
    attrs = {
        "klib": attr.label(
            mandatory = True,
            allow_single_file = [".klib"],
            doc = "The `.klib` of the library, compiled for [konan_target].",
        ),
        "source_jar": attr.label(
            allow_single_file = [".jar"],
            doc = "The sources jar of the library, when fetched.",
        ),
        "konan_target": attr.string(
            mandatory = True,
            doc = "The Kotlin/Native target the klib was compiled for, e.g. `ios_arm64`.",
        ),
        "deps": attr.label_list(
            doc = "The library's link dependencies.",
        ),
        "exported_deps": attr.label_list(
            doc = "The dependencies compile-visible to the dependents of this library.",
        ),
    },
    doc = "A prebuilt `.klib`, its files and dependency tree exposed through `KlibInfo`.",
)

def _klib_group_impl(ctx):
    """A library of the target carrying no klib, only dependencies: it redirects to them."""
    return [
        DefaultInfo(files = _transitive_files(ctx.attr.deps + ctx.attr.exported_deps)),
        KlibInfo(
            klib = None,
            source_jar = None,
            konan_target = None,
            deps = _transitive_klibinfos(ctx.attr.deps, "deps"),
            exported_deps = _transitive_klibinfos(ctx.attr.exported_deps, "exported_deps"),
        ),
    ]

klib_library = rule(
    implementation = _klib_group_impl,
    attrs = {
        "deps": attr.label_list(
            doc = "The library's link dependencies.",
        ),
        "exported_deps": attr.label_list(
            doc = "The dependencies compile-visible to the dependents of this library.",
        ),
    },
    doc = "A redirect to the library's dependencies: no klib of its own, only dependencies.",
)
