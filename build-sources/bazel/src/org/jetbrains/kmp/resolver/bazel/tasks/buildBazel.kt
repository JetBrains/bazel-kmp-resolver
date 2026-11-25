package org.jetbrains.kmp.resolver.bazel.tasks

import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.TaskAction

/**
 * Builds the Bazel side of rules_kotlin_external against the freshly built resolver: the task graph
 * builds the native image first — the [nativeBinaryDirectory] input is the `buildNativeImage`
 * task's output directory, wiring it as a dependency, incrementally cached — then it is staged into
 * the example workspaces and the module workspace and every example are built, each target
 * exercising the resolution, the artifact downloads and the import-rule wiring of its platforms.
 *
 * Execution avoidance is disabled: the task verifies the current state of the rules and the
 * examples, Bazel itself caches whatever did not change.
 */
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun buildBazel(
    @Input nativeBinaryDirectory: Path,
    @Input rulesDirectory: Path,
): Unit = runBlocking {
    val binary = resolverBinary(nativeBinaryDirectory)
    stageResolverIntoExamples(rulesDirectory, binary)

    // The module workspace: the rules, the import rules, and the Starlark unit test targets.
    runBazel(rulesDirectory, "build", "//...")

    // The examples: one workspace per platform, building the targets named in its BUILD.bazel.
    for ((example, targets) in EXAMPLE_TARGETS) {
        val workspace = rulesDirectory.resolve("examples").resolve(example)
        check(workspace.resolve("MODULE.bazel").isRegularFile()) {
            "No example workspace at ${workspace}"
        }
        runBazel(workspace, "build", *targets.toTypedArray())
    }
}

/**
 * The example workspaces and the targets to build in each: the resolved libraries of the platforms,
 * named as in the examples' BUILD.bazel files.
 */
private val EXAMPLE_TARGETS: Map<String, List<String>> =
    mapOf(
        // A JVM library compiling against the resolved coroutines jar.
        "jvm" to listOf("//:app"),
        // The wasmjs klib and the NPM packages of its embedded package.json.
        "wasmjs" to listOf("@kmp_deps_wasmjs//:org_jetbrains_kotlinx_kotlinx_datetime"),
        // SQLiter's klib and its C-interop klib, coroutines' klib, on two konan targets.
        "native" to
            listOf(
                "@kmp_deps_ios_arm64//:co_touchlab_sqliter_driver",
                "@kmp_deps_ios_arm64//:co_touchlab_sqliter_driver_iosarm64_1_4_0_cinterop_0",
                "@kmp_deps_macos_arm64//:org_jetbrains_kotlinx_kotlinx_coroutines_core",
            ),
        // An `.aar`, a jar fallback, and a library redirecting to its AndroidX dependencies.
        "android" to
            listOf(
                "@kmp_deps_android//:androidx_annotation_annotation_experimental",
                "@kmp_deps_android//:androidx_annotation_annotation",
                "@kmp_deps_android//:org_jetbrains_compose_runtime_runtime",
            ),
    )
