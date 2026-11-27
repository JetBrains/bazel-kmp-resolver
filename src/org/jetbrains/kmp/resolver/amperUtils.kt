package org.jetbrains.kmp.resolver

import org.jetbrains.amper.dependency.resolution.DependencyFile
import org.jetbrains.amper.dependency.resolution.DependencyFileImpl
import org.jetbrains.amper.dependency.resolution.MavenDependencyImpl
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.group
import org.jetbrains.amper.dependency.resolution.module
import org.jetbrains.amper.dependency.resolution.orUnspecified
import org.jetbrains.amper.dependency.resolution.resolvedVersion
import org.jetbrains.gradle.module.metadata.format.Variant

internal val MavenDependencyNode.gav
    get(): MultiplatformLibraryId =
        MultiplatformLibraryId(
            groupId = group,
            artifactId = module,
            version = resolvedVersion().orUnspecified(),
        )

// Nodes are collected per platform, so a node's files all belong to the platform being resolved.
internal fun DependencyFileImpl.isSourceJar(): Boolean =
    isDocumentation && extension == "jar" && nameWithoutExtension.endsWith("-sources")

internal fun DependencyFileImpl.isClasspathJar(): Boolean = !isDocumentation && extension == "jar"

internal fun DependencyFile.isKlib(): Boolean = extension == "klib"

internal fun DependencyFile.isAar(): Boolean = !isDocumentation && extension == "aar"

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
internal fun MavenDependencyImpl.resolvedVariants(): List<Variant> = this.variants
