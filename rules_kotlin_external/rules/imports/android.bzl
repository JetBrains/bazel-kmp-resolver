"""Default Android import rules, the instantiation target of the `android` platform hub repositories.

Deliberately minimal: `aar_import` exposes the `.aar` and its dependency tree as files and an
`AarInfo` provider, enough for classpath-style consumption and IDE indexing. Real Android builds
plug in rules_android's rules through the `android_import_bzl` label of the `kmp.install(...)` tag;
Android libraries publishing no `.aar` fall back to the JVM import rules.
"""

AarInfo = provider(
    doc = "An imported `.aar` Android library and its dependency tree.",
    fields = {
        "aar": "The `.aar` file of the library, None for a redirect to its dependencies.",
        "source_jar": "The sources jar of the library, when fetched.",
        "deps": "The `AarInfo`s of the library's runtime dependencies (depset).",
        "exported_deps": "The `AarInfo`s of the dependencies compile-visible to dependents (depset).",
    },
)

def _transitive_files(deps):
    return depset(transitive = [dep[DefaultInfo].files for dep in deps])

def _transitive_ainfos(deps, field):
    # Tolerates dependencies without `AarInfo` (e.g. a jar-fallback target of the JVM import rules).
    return depset(
        [dep[AarInfo] for dep in deps if AarInfo in dep],
        transitive = [getattr(dep[AarInfo], field) for dep in deps if AarInfo in dep],
    )

def _aar_import_impl(ctx):
    files = [ctx.file.aar]
    if ctx.file.source_jar != None:
        files.append(ctx.file.source_jar)
    return [
        DefaultInfo(
            files = depset(files, transitive = [_transitive_files(ctx.attr.deps + ctx.attr.exported_deps)]),
        ),
        AarInfo(
            aar = ctx.file.aar,
            source_jar = ctx.file.source_jar,
            deps = _transitive_ainfos(ctx.attr.deps, "deps"),
            exported_deps = _transitive_ainfos(ctx.attr.exported_deps, "exported_deps"),
        ),
    ]

aar_import = rule(
    implementation = _aar_import_impl,
    attrs = {
        "aar": attr.label(
            mandatory = True,
            allow_single_file = [".aar"],
            doc = "The `.aar` of the library.",
        ),
        "source_jar": attr.label(
            allow_single_file = [".jar"],
            doc = "The sources jar of the library, when fetched.",
        ),
        "deps": attr.label_list(
            doc = "The library's runtime dependencies.",
        ),
        "exported_deps": attr.label_list(
            doc = "The dependencies compile-visible to the dependents of this library.",
        ),
    },
    doc = "A prebuilt `.aar` Android library, its files and dependency tree exposed through `AarInfo`.",
)

def _android_group_impl(ctx):
    """A library of the platform carrying no `.aar`, only dependencies (e.g. JetBrains Compose's
    Android variants redirecting to the AndroidX publications)."""
    return [
        DefaultInfo(files = _transitive_files(ctx.attr.deps + ctx.attr.exported_deps)),
        AarInfo(
            aar = None,
            source_jar = None,
            deps = _transitive_ainfos(ctx.attr.deps, "deps"),
            exported_deps = _transitive_ainfos(ctx.attr.exported_deps, "exported_deps"),
        ),
    ]

android_library = rule(
    implementation = _android_group_impl,
    attrs = {
        "deps": attr.label_list(
            doc = "The library's runtime dependencies.",
        ),
        "exported_deps": attr.label_list(
            doc = "The dependencies compile-visible to the dependents of this library.",
        ),
    },
    doc = "A redirect to the library's dependencies: no `.aar` of its own, only dependencies.",
)
