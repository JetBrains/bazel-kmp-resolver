"""Registration of the artifact repositories of a resolution, ported from intellij-community's
`fleet/build/rules/kmp.bzl` and adapted to the resolver's polymorphic manifest.

Every Maven artifact becomes one `http_file` repository shared by the per-platform hub repositories;
every NPM package of a `wasm_js` variant becomes one `http_archive` repository of its registry
tarball. Repository names are prefixed with the install's name, so several installs can resolve
different versions of the same module without colliding; Bazel's repository cache dedupes the
actual downloads.
"""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive", "http_file")
load(":manifest.bzl", "libraries", "maven_artifacts", "npm_packages", "platform_variant")
load(":naming.bzl", "artifact_classifier", "basename_from_url", "repository_name_part")

def register_artifact_repositories(install_name, resolution):
    """Creates the `http_file`/`http_archive` repositories of all artifacts of [resolution]."""
    artifacts = collect_artifacts(resolution)
    packages = collect_npm_packages(resolution)

    used_names = {}
    for artifact_key in sorted(artifacts.keys()):
        artifact = artifacts[artifact_key]
        urls = artifact["urls"]
        repo_name = artifact_repository_name(install_name, artifact)
        if repo_name in used_names and used_names[repo_name] != artifact_key:
            fail("Artifact repository name collision for '%s' and '%s': %s" % (
                used_names[repo_name],
                artifact_key,
                repo_name,
            ))
        used_names[repo_name] = artifact_key
        http_file(
            name = repo_name,
            downloaded_file_path = basename_from_url(urls[0]),
            integrity = artifact["integrity"],
            urls = urls,
        )

    for package_key in sorted(packages.keys()):
        package = packages[package_key]
        repo_name = npm_repository_name(install_name, package)
        if repo_name in used_names and used_names[repo_name] != package_key:
            fail("Artifact repository name collision for '%s' and '%s': %s" % (
                used_names[repo_name],
                package_key,
                repo_name,
            ))
        used_names[repo_name] = package_key
        http_archive(
            name = repo_name,
            url = package["url"],
            integrity = package["integrity"],
            # npm tarballs place the package content under a `package/` root directory.
            strip_prefix = "package",
            build_file = Label("//rules:npm.BUILD.bazel"),
        )

def collect_artifacts(resolution):
    """Maven artifacts of all variants of all libraries, keyed by `[groupId, artifactId, version, basename]`.

    One `http_file` per artifact is shared by the platform hub repositories. The same artifact
    appearing in several variants (or libraries) must carry the same integrity, or the resolution
    is contradictory.
    """
    artifacts = {}
    for library_id in sorted(libraries(resolution).keys()):
        library = libraries(resolution)[library_id]
        for variant in library["variants"]:
            for artifact in maven_artifacts(variant):
                _add_artifact(artifacts, library_id, artifact)
    return artifacts

def _add_artifact(artifacts, library_id, artifact):
    artifact_key = json.encode([
        artifact["groupId"],
        artifact["artifactId"],
        artifact["version"],
        basename_from_url(artifact["urls"][0]),
    ])
    existing = artifacts.get(artifact_key)
    if existing != None:
        if existing["integrity"] != artifact["integrity"]:
            fail("Artifact checksum collision for %s, declared by library %s" % (artifact_key, library_id))
        return
    artifacts[artifact_key] = artifact

def collect_npm_packages(resolution):
    """NPM packages of the `wasm_js` variants of all libraries, keyed by `[name, version]`."""
    packages = {}
    for library_id in sorted(libraries(resolution).keys()):
        library = libraries(resolution)[library_id]
        variant = platform_variant(library, "wasmjs")
        if variant == None:
            continue

        for package in npm_packages(variant):
            package_key = json.encode([package["name"], package["version"]])
            existing = packages.get(package_key)
            if existing != None:
                if existing["integrity"] != package["integrity"] or existing["url"] != package["url"]:
                    fail("NPM package resolution collision for %s, declared by library %s" % (package_key, library_id))
                continue
            packages[package_key] = package
    return packages

def artifact_repository_name(install_name, artifact):
    """`<install>_<group>_<artifact>_<version>[_<classifier>]_http`, e.g.
    `kmp_deps_io_ktor_ktor_client_core_jvm_3_6_0_http`."""
    artifact_id = artifact["artifactId"]
    version = artifact["version"]
    classifier = artifact_classifier(basename_from_url(artifact["urls"][0]), artifact_id, version)
    classifier_suffix = "" if not classifier else "_%s" % repository_name_part(classifier)
    return "%s_%s_%s_%s%s_http" % (
        repository_name_part(install_name),
        repository_name_part(artifact["groupId"]),
        repository_name_part(artifact_id),
        repository_name_part(version),
        classifier_suffix,
    )

def npm_repository_name(install_name, package):
    """`<install>_npm_<name>_<version>_http`, e.g. `kmp_deps_npm_js_joda_core_3_2_0_http`."""
    return "%s_npm_%s_%s_http" % (
        repository_name_part(install_name),
        repository_name_part(package["name"]),
        repository_name_part(package["version"]),
    )

def artifact_label(install_name, artifact):
    """The label of the `http_file` target serving an artifact."""
    return "@%s//file:file" % artifact_repository_name(install_name, artifact)

def npm_label(install_name, package):
    """The label of the `http_archive` target exposing an NPM package's files."""
    return "@%s//:package" % npm_repository_name(install_name, package)
