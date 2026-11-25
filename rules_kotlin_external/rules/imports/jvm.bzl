"""Default JVM import rules, the instantiation target of the `jvm` platform hub repositories.

`jvm_import` wraps a prebuilt jar (and its sources jar) in a `JavaInfo`; `jvm_library` carries the
library's exported and runtime-only dependencies, forwarded to `@rules_java`'s `java_library`.
Swap both through the `jvm_import_bzl` label of the `kmp.install(...)` tag.
"""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("@rules_java//java:defs.bzl", _java_library = "java_library")

def _jvm_import_impl(ctx):
    jar = ctx.file.jar
    java_info = JavaInfo(output_jar = jar, compile_jar = jar)
    files = [jar]
    if ctx.file.source_jar != None:
        java_info = JavaInfo(output_jar = jar, compile_jar = jar, source_jar = ctx.file.source_jar)
        files.append(ctx.file.source_jar)
    return [
        java_info,
        DefaultInfo(files = depset(files)),
    ]

jvm_import = rule(
    implementation = _jvm_import_impl,
    attrs = {
        "jar": attr.label(
            mandatory = True,
            allow_single_file = [".jar"],
            doc = "The prebuilt jar of the library.",
        ),
        "source_jar": attr.label(
            allow_single_file = [".jar"],
            doc = "The sources jar of the library, when fetched.",
        ),
    },
    doc = """A prebuilt JVM library jar with its `JavaInfo`; the hub repositories wrap it in a
    `jvm_library` carrying the library's dependencies.""",
)

# `jvm_import` has no `exports`, hence the wrapper: `exportedDependencies` go to the wrapper's
# `exports`, `dependencies` to its `runtime_deps`.
jvm_library = _java_library
