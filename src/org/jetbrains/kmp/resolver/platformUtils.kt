package org.jetbrains.kmp.resolver

import org.jetbrains.amper.dependency.resolution.PlatformType
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform

internal val nativeResolutionPlatforms =
    ResolutionPlatform.entries.filter { it.type == PlatformType.NATIVE }

/*
 * Platforms the resolver knows how to collect libraries for, in the order their variants
 * are listed in the manifest.
 */
internal val supportedPlatforms: List<ResolutionPlatform> = buildList {
    add(ResolutionPlatform.WASM_JS)
    add(ResolutionPlatform.JVM)
    add(ResolutionPlatform.ANDROID)
    addAll(nativeResolutionPlatforms)
}

internal val ResolutionPlatform.cliName: String
    get() {
        val nativeTarget = nativeTarget
        return when {
            this == ResolutionPlatform.WASM_JS -> "wasmjs"
            nativeTarget != null -> nativeTarget
            else -> this.name.lowercase()
        }
    }

internal fun resolutionPlatformOf(raw: String): ResolutionPlatform? {
    return ResolutionPlatform.entries.firstOrNull { platform ->
        platform.cliName.equals(raw, ignoreCase = true) ||
            platform.name.equals(raw, ignoreCase = true)
    }
}
