"""Default WasmJS import rules, the instantiation target of the `wasmjs` platform hub repositories.

Deliberately minimal: `wasmjs_import` exposes the `.klib`, the NPM packages declared by the
`package.json` embedded in it, and the dependency tree as files and a `WasmJsInfo` provider. Real
compilation and linking plug in a toolchain's import rules — e.g. rules_jvm's `wasmjs_import`, as
the intellij-community setup does — through the `wasmjs_import_bzl` label of the `kmp.install(...)`
tag.
"""

WasmJsInfo = provider(
    doc = "An imported `wasmjs` klib, its NPM packages, and its dependency tree.",
    fields = {
        "klib": "The `.klib` file of the library, None for a redirect to its dependencies.",
        "source_jar": "The sources jar of the library, when fetched.",
        "npm_packages": "The NPM package targets the klib links against (dict of package name to Target).",
        "deps": "The `WasmJsInfo`s of the library's link dependencies (depset).",
        "exported_deps": "The `WasmJsInfo`s of the dependencies compile-visible to dependents (depset).",
    },
)

def _transitive_files(deps):
    return depset(transitive = [dep[DefaultInfo].files for dep in deps])

def _transitive_wasmjsinfos(deps, field):
    return depset(
        [dep[WasmJsInfo] for dep in deps if WasmJsInfo in dep],
        transitive = [getattr(dep[WasmJsInfo], field) for dep in deps if WasmJsInfo in dep],
    )

def _wasmjs_import_impl(ctx):
    files = [ctx.file.klib]
    if ctx.file.source_jar != None:
        files.append(ctx.file.source_jar)
    npm_package_files = depset(transitive = [
        package[DefaultInfo].files
        for package in ctx.attr.npm_packages.values()
    ])
    return [
        DefaultInfo(
            files = depset(
                files,
                transitive = [
                    npm_package_files,
                    _transitive_files(ctx.attr.deps + ctx.attr.exported_deps),
                ],
            ),
        ),
        WasmJsInfo(
            klib = ctx.file.klib,
            source_jar = ctx.file.source_jar,
            npm_packages = ctx.attr.npm_packages,
            deps = _transitive_wasmjsinfos(ctx.attr.deps, "deps"),
            exported_deps = _transitive_wasmjsinfos(ctx.attr.exported_deps, "exported_deps"),
        ),
    ]

wasmjs_import = rule(
    implementation = _wasmjs_import_impl,
    attrs = {
        "klib": attr.label(
            mandatory = True,
            allow_single_file = [".klib"],
            doc = "The `wasmjs` `.klib` of the library.",
        ),
        "source_jar": attr.label(
            allow_single_file = [".jar"],
            doc = "The sources jar of the library, when fetched.",
        ),
        "npm_packages": attr.string_keyed_label_dict(
            doc = "The NPM packages declared by the `package.json` embedded in the klib, keyed by package name.",
        ),
        "deps": attr.label_list(
            doc = "The library's link dependencies.",
        ),
        "exported_deps": attr.label_list(
            doc = "The dependencies compile-visible to the dependents of this library.",
        ),
    },
    doc = "A prebuilt `wasmjs` klib, its NPM packages and dependency tree exposed through `WasmJsInfo`.",
)

def _wasmjs_group_impl(ctx):
    """A library of the platform carrying no klib, only dependencies: it redirects to them."""
    return [
        DefaultInfo(files = _transitive_files(ctx.attr.deps + ctx.attr.exported_deps)),
        WasmJsInfo(
            klib = None,
            source_jar = None,
            npm_packages = {},
            deps = _transitive_wasmjsinfos(ctx.attr.deps, "deps"),
            exported_deps = _transitive_wasmjsinfos(ctx.attr.exported_deps, "exported_deps"),
        ),
    ]

wasmjs_library = rule(
    implementation = _wasmjs_group_impl,
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
