import org.gradle.internal.hash.Hashing
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import kotlin.io.encoding.Base64
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText

plugins {
    kotlin("multiplatform") apply false
}

val resolveDependencies = tasks.register("resolveDependencies") {
    description = "Resolves dependencies and creates a dump file per target"
}

extensions.getByType<KotlinMultiplatformExtension>().targets.all {
    when (platformType) {
        KotlinPlatformType.common -> Unit
        else -> {
            val resolveTargetDependenciesTask = tasks.register("resolveDependencies_$targetName") {
                description = "Resolves dependencies for the $targetName and creates a dump file per target"

                /* Resolving all compile dependencies */
                val compileLibraries = project.configurations.getByName(
                    compilations.getByName("main").compileDependencyConfigurationName
                ).incoming.artifactView {
                    componentFilter { identifier -> identifier is ModuleComponentIdentifier }
                }.artifacts

                /*
                Resolving all runtime dependencies: Note: Some targets (e.g. native) may not have runtime
                dependencies, as there is only one scope supported.
                 */
                val runtimeLibraries = run {
                    project.configurations.getByName(
                        compilations.getByName("main").runtimeDependencyConfigurationName ?: return@run null
                    ).incoming.artifactView {
                        componentFilter { identifier -> identifier is ModuleComponentIdentifier }
                    }
                }?.artifacts

                val compileOutputFile = project.rootDir.resolve("golden-data/${project.name}/$targetName.compile.txt")
                val runtimeOutputFile = project.rootDir.resolve("golden-data/${project.name}/$targetName.runtime.txt")

                inputs.files(compileLibraries.artifactFiles)
                inputs.files(runtimeLibraries?.artifactFiles ?: files())
                outputs.file(compileOutputFile)
                outputs.file(runtimeOutputFile)

                data class Entry(
                    val gav: String,
                    val sha512: String,
                    val sha256: String,
                    val sha1: String,
                )

                doLast {
                    fun createDump(artifacts: ArtifactCollection, outputFile: File) {
                        val entries = artifacts.map { artifactResult ->
                            fun ByteArray.base64(): String {
                                return Base64.encode(this)
                            }

                            (artifactResult.variant.owner as ModuleComponentIdentifier).let {
                                Entry(
                                    gav = "${it.group}:${it.module}:${it.version}",
                                    sha512 = Hashing.sha512().hashFile(artifactResult.file).toByteArray().base64(),
                                    sha256 = Hashing.sha256().hashFile(artifactResult.file).toByteArray().base64(),
                                    sha1 = Hashing.sha1().hashFile(artifactResult.file).toByteArray().base64(),
                                )
                            }
                        }.sortedBy { it.gav }

                        outputFile.toPath().createParentDirectories()
                            .writeText(entries.joinToString(System.lineSeparator()) { entry ->
                                buildString {
                                    appendLine(entry.gav)
                                    appendLine("sha512-${entry.sha512}")
                                    appendLine("sha256-${entry.sha256}")
                                    appendLine("sha1-${entry.sha1}")
                                }
                            })
                    }

                    createDump(compileLibraries, compileOutputFile)
                    if (runtimeLibraries != null) {
                        createDump(runtimeLibraries, runtimeOutputFile)
                    }
                }
            }

            resolveDependencies.configure {
                dependsOn(resolveTargetDependenciesTask)
            }
        }
    }
}
